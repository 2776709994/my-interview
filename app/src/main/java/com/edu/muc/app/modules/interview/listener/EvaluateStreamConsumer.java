package com.edu.muc.app.modules.interview.listener;

import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.common.evaluation.EvaluationReport;
import com.edu.muc.app.common.evaluation.QaRecord;
import com.edu.muc.app.common.evaluation.UnifiedEvaluationService;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.interview.domain.InterviewAnswer;
import com.edu.muc.app.modules.interview.domain.InterviewQuestion;
import com.edu.muc.app.modules.interview.domain.InterviewSession;
import com.edu.muc.app.modules.interview.enums.EvaluateStatus;
import com.edu.muc.app.modules.interview.enums.SessionStatus;
import com.edu.muc.app.modules.interview.mapper.InterviewAnswerMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewQuestionMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 面试评估异步消费者（Redis Stream 模板方法实现）
 * <p>
 * 状态机：PENDING →（领取，带状态守卫）→ PROCESSING → COMPLETED/FAILED（超过 3 次重试）。
 * 评估优先使用 {@link UnifiedEvaluationService}（分批评估 + 结构化输出重试 + 二次汇总），
 * 调用失败时降级为内置的简易评估逻辑；白卷直接生成零分报告，不消耗 AI 调用。
 * </p>
 */
@Component
@Slf4j
public class EvaluateStreamConsumer extends AbstractStreamConsumer<String> {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ChatClient chatClient;
    private final UnifiedEvaluationService unifiedEvaluationService;
    private final ExecutorService evaluationExecutor;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EvaluateStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                  StreamPendingRecoverer pendingRecoverer,
                                  InterviewSessionMapper sessionMapper,
                                  InterviewQuestionMapper questionMapper,
                                  InterviewAnswerMapper answerMapper,
                                  ChatClient chatClient,
                                  UnifiedEvaluationService unifiedEvaluationService,
                                  @Qualifier("interviewEvaluationExecutor") ExecutorService evaluationExecutor) {
        super(redisTemplate, pendingRecoverer);
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.chatClient = chatClient;
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.evaluationExecutor = evaluationExecutor;
    }

    @Override
    protected ExecutorService executor() {
        return evaluationExecutor;
    }

    @Override
    protected String taskDisplayName() {
        return "面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId);
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "interview-evaluation-listener";
    }

    @Override
    protected String parsePayload(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        return raw == null ? null : raw.toString().trim();
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    @Override
    protected boolean shouldSkip(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        return session != null && EvaluateStatus.COMPLETED.getCode().equals(session.getEvaluateStatus());
    }

    @Override
    protected boolean tryMarkProcessing(String sessionId) {
        // 状态守卫：仅 PENDING/PROCESSING（重试/宕机重投）或未初始化时允许领取
        return sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getSessionId, sessionId)
                .and(w -> w.isNull(InterviewSession::getEvaluateStatus)
                        .or()
                        .in(InterviewSession::getEvaluateStatus,
                                EvaluateStatus.PENDING.getCode(), EvaluateStatus.PROCESSING.getCode()))
                .set(InterviewSession::getEvaluateStatus, EvaluateStatus.PROCESSING.getCode())) > 0;
    }

    @Override
    protected void processBusiness(String sessionId) throws Exception {
        log.info("🚀 开始处理面试评估, sessionId: {}", sessionId);

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            // 不静默丢弃：抛异常走自动重试，避免主从延迟/时序问题导致任务永久丢失
            throw new IllegalStateException("面试会话不存在: " + sessionId);
        }

        // 1. 获取所有问题和答案
        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .orderByAsc(InterviewQuestion::getQuestionIndex));
        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .orderByAsc(InterviewAnswer::getQuestionIndex));
        log.info("📊 查询结果 - 问题数: {}, 答案数: {}", questions.size(), answers.size());

        if (questions.isEmpty()) {
            throw new BusinessException("NO_QUESTIONS_FOUND", "会话没有面试问题，无法评估");
        }

        // 2. 白卷处理：生成零分评估报告，不消耗 AI 调用
        if (answers.isEmpty()) {
            log.warn("⚠️ 会话 {} 用户未回答任何问题，生成零分评估报告", sessionId);
            generateEmptyEvaluation(session, questions);
            return;
        }

        // 3. 优先使用统一评估服务（分批评估 + 结构化输出重试 + 二次汇总）
        try {
            List<QaRecord> qaRecords = buildQaRecords(questions, answers);
            EvaluationReport report = unifiedEvaluationService.evaluate(
                    chatClient, sessionId, qaRecords, session.getResumeText());
            applyEvaluationReport(session, answers, report);
            log.info("✅ 统一评估服务评估完成, sessionId: {}", sessionId);
            return;
        } catch (Exception e) {
            log.warn("⚠️ 统一评估服务调用失败，降级为简易评估, sessionId: {}, error: {}",
                    sessionId, e.getMessage());
        }

        // 4. 降级：简易评估（单次 AI 调用 + 手写 prompt）
        generateEvaluationLegacy(session, questions, answers);
        log.info("✅ 评估报告生成完成（简易评估）, sessionId: {}", sessionId);
    }

    @Override
    protected void markCompleted(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        session.setEvaluateStatus(EvaluateStatus.COMPLETED.getCode());
        session.setStatus(SessionStatus.EVALUATED.getCode());
        session.setEvaluatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    protected void markFailed(String sessionId, String error) {
        log.error("❌ 面试评估最终失败: sessionId={}, 原因: {}", sessionId, error);
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setEvaluateStatus(EvaluateStatus.FAILED.getCode());
            session.setEvaluateError(error);
            sessionMapper.updateById(session);
        }
    }

    /**
     * 将问题与答案按 questionIndex 对齐为统一评估服务的问答记录。
     * 未作答的问题保留（userAnswer = null），由评估服务按 0 分处理。
     */
    private List<QaRecord> buildQaRecords(List<InterviewQuestion> questions, List<InterviewAnswer> answers) {
        Map<Integer, InterviewAnswer> answerMap = new HashMap<>();
        for (InterviewAnswer a : answers) {
            answerMap.put(a.getQuestionIndex(), a);
        }
        List<QaRecord> records = new ArrayList<>();
        for (InterviewQuestion q : questions) {
            InterviewAnswer a = answerMap.get(q.getQuestionIndex());
            records.add(new QaRecord(
                    q.getQuestionIndex(),
                    q.getQuestion(),
                    q.getCategory(),
                    a != null ? a.getAnswer() : null
            ));
        }
        return records;
    }

    /**
     * 将统一评估服务的报告写回答案与题目表（会话终态由 markCompleted 统一落库）
     */
    private void applyEvaluationReport(InterviewSession session, List<InterviewAnswer> answers,
                                       EvaluationReport report) {
        String sessionId = session.getSessionId();
        session.setOverallScore(report.overallScore());
        session.setOverallFeedback(report.overallFeedback());

        try {
            session.setStrengthsJson(MAPPER.writeValueAsString(
                    report.strengths() != null ? report.strengths() : List.of()));
            session.setImprovementsJson(MAPPER.writeValueAsString(
                    report.improvements() != null ? report.improvements() : List.of()));
        } catch (Exception e) {
            log.warn("序列化 strengths/improvements 失败: {}", e.getMessage());
            session.setStrengthsJson("[]");
            session.setImprovementsJson("[]");
        }
        sessionMapper.updateById(session);

        // 按题目更新答案评分与反馈
        Map<Integer, InterviewAnswer> answerMap = new HashMap<>();
        for (InterviewAnswer a : answers) {
            answerMap.put(a.getQuestionIndex(), a);
        }
        for (EvaluationReport.QuestionEvaluation qe : report.questionDetails()) {
            InterviewAnswer answer = answerMap.get(qe.questionIndex());
            if (answer != null) {
                answer.setScore(qe.score());
                answer.setFeedback(qe.feedback());
                answer.setEvaluatedAt(LocalDateTime.now());
                answerMapper.updateById(answer);
            }
        }

        // 写回参考答案与要点（报告页展示），按 questionIndex 更新题目表
        if (report.referenceAnswers() != null && !report.referenceAnswers().isEmpty()) {
            Map<Integer, InterviewQuestion> questionMap = new HashMap<>();
            for (InterviewQuestion q : questionMapper.selectList(
                    new LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, sessionId))) {
                questionMap.put(q.getQuestionIndex(), q);
            }
            for (EvaluationReport.ReferenceAnswer ra : report.referenceAnswers()) {
                InterviewQuestion q = questionMap.get(ra.questionIndex());
                if (q == null) {
                    continue;
                }
                q.setReferenceAnswer(ra.referenceAnswer());
                try {
                    q.setKeyPointsJson(MAPPER.writeValueAsString(
                            ra.keyPoints() != null ? ra.keyPoints() : List.of()));
                } catch (Exception e) {
                    log.warn("序列化 keyPoints 失败: {}", e.getMessage());
                    q.setKeyPointsJson("[]");
                }
                questionMapper.updateById(q);
            }
        }
    }

    /**
     * 简易评估（统一评估服务失败时的降级方案）
     */
    private void generateEvaluationLegacy(InterviewSession session, List<InterviewQuestion> questions,
                                          List<InterviewAnswer> answers) throws Exception {
        String sessionId = session.getSessionId();
        StringBuilder evaluationPrompt = new StringBuilder();
        evaluationPrompt.append("请对以下面试回答进行评估：\n\n");

        for (int i = 0; i < questions.size(); i++) {
            InterviewQuestion q = questions.get(i);
            InterviewAnswer a = answers.stream()
                    .filter(ans -> ans.getQuestionIndex().equals(q.getQuestionIndex()))
                    .findFirst()
                    .orElse(null);

            if (a != null) {
                evaluationPrompt.append(String.format(
                        "问题 %d：%s\n类别：%s\n回答：%s\n\n",
                        i + 1, q.getQuestion(), q.getCategory(), a.getAnswer()
                ));
            }
        }

        evaluationPrompt.append("\n请输出 JSON 格式，包含：");
        evaluationPrompt.append("overallScore（总分0-100）, overallFeedback, strengths（数组）, improvements（数组）, ");
        evaluationPrompt.append("questionEvaluations（数组，每项包含questionIndex, score, feedback）");

        log.info("🤖 调用 AI 进行评估（简易模式）...");
        String aiResponse = chatClient.prompt()
                .system("你是一位资深技术面试官，请客观、专业地评估候选人的回答。")
                .user(evaluationPrompt.toString())
                .call()
                .content();
        if (aiResponse == null || aiResponse.isBlank()) {
            throw new BusinessException("AI_EMPTY_RESPONSE", "AI 评估返回空响应");
        }
        log.info("✅ AI 评估完成，响应长度: {}", aiResponse.length());

        // 解析评估结果
        String jsonStr = JsonUtils.extractJson(aiResponse);
        Map<String, Object> result = MAPPER.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});

        // 更新会话（安全类型转换）
        Object scoreObj = result.getOrDefault("overallScore", 0);
        session.setOverallScore(scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 0);
        session.setOverallFeedback((String) result.get("overallFeedback"));

        @SuppressWarnings("unchecked")
        List<String> strengths = (List<String>) result.get("strengths");
        session.setStrengthsJson(MAPPER.writeValueAsString(strengths != null ? strengths : List.of()));

        @SuppressWarnings("unchecked")
        List<String> improvements = (List<String>) result.get("improvements");
        session.setImprovementsJson(MAPPER.writeValueAsString(improvements != null ? improvements : List.of()));
        sessionMapper.updateById(session);

        // 更新每个问题的评分
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questionEvaluations =
                (List<Map<String, Object>>) result.get("questionEvaluations");

        if (questionEvaluations != null) {
            for (Map<String, Object> eval : questionEvaluations) {
                int qIndex = JsonUtils.safeGetInt(eval.get("questionIndex"));
                double score = JsonUtils.safeGetDouble(eval.get("score"));
                String feedback = (String) eval.get("feedback");

                InterviewAnswer answer = answerMapper.selectOne(
                        new LambdaQueryWrapper<InterviewAnswer>()
                                .eq(InterviewAnswer::getSessionId, sessionId)
                                .eq(InterviewAnswer::getQuestionIndex, qIndex));

                if (answer != null) {
                    answer.setScore((int) score);
                    answer.setFeedback(feedback);
                    answer.setEvaluatedAt(LocalDateTime.now());
                    answerMapper.updateById(answer);
                }
            }
        }
    }

    /**
     * 生成白卷评估报告（零分）
     */
    private void generateEmptyEvaluation(InterviewSession session, List<InterviewQuestion> questions) {
        try {
            session.setOverallScore(0);
            session.setOverallFeedback("候选人未回答任何问题，无法进行有效评估。建议认真准备面试，积极回答问题以展示自己的能力。");

            session.setStrengthsJson(MAPPER.writeValueAsString(List.of()));

            List<String> improvements = List.of(
                "面试中应积极回答问题，即使不确定也要尝试表达自己的思路",
                "建议提前准备常见面试题，增强自信心",
                "遇到不会的问题可以请求提示或换个角度思考",
                "保持冷静，不要因为紧张而放弃回答"
            );
            session.setImprovementsJson(MAPPER.writeValueAsString(improvements));
            sessionMapper.updateById(session);

            // 为所有问题创建零分答案记录
            for (InterviewQuestion question : questions) {
                InterviewAnswer answer = new InterviewAnswer();
                answer.setSessionId(session.getSessionId());
                answer.setQuestionIndex(question.getQuestionIndex());
                answer.setAnswer("");
                answer.setScore(0);
                answer.setFeedback("未作答");
                answer.setCreatedAt(LocalDateTime.now());
                answer.setEvaluatedAt(LocalDateTime.now());
                answerMapper.insert(answer);
            }

            log.info("✅ 白卷评估报告生成完成, sessionId: {}, 总分: 0", session.getSessionId());
        } catch (Exception e) {
            throw new BusinessException("EMPTY_EVALUATION_FAILED", "生成白卷评估报告失败: " + e.getMessage());
        }
    }
}

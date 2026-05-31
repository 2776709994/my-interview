package com.edu.muc.app.modules.interview.service;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 面试评估异步消费者
 * 监听 Redis Stream: interview:evaluation
 * <p>
 * 优先使用 {@link UnifiedEvaluationService}（分批评估 + 结构化输出重试 + 二次汇总），
 * 调用失败时降级为内置的简易评估逻辑，保证评估任务不因单次异常而失败。
 * </p>
 */
@Component
@Slf4j
public class InterviewEvaluationConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final StreamPendingRecoverer pendingRecoverer;
    private final UnifiedEvaluationService unifiedEvaluationService;
    
    private static final String STREAM_KEY = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    private static final String GROUP = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Thread listenerThread;

    public InterviewEvaluationConsumer(RedisTemplate<String, Object> redisTemplate,
                                      InterviewSessionMapper sessionMapper,
                                      InterviewQuestionMapper questionMapper,
                                      InterviewAnswerMapper answerMapper,
                                      ChatClient chatClient,
                                      StreamPendingRecoverer pendingRecoverer,
                                      UnifiedEvaluationService unifiedEvaluationService,
                                      @org.springframework.beans.factory.annotation.Qualifier("interviewEvaluationExecutor") 
                                      ExecutorService executor) {
        this.redisTemplate = redisTemplate;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.chatClient = chatClient;
        this.pendingRecoverer = pendingRecoverer;
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.executor = executor;
    }

    @PostConstruct
    public void start() {
        log.info("✅ Redis Stream 面试评估消费者已启动，开始监听 {}", STREAM_KEY);
        
        // 1. 确保消费者组存在
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                log.warn("创建消费者组异常（可能已存在）: {}", e.getMessage());
            }
        }

        // 2. 启动独立线程持续监听
        String consumerName = AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        listenerThread = new Thread(() -> {
            int loopCount = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                            .read(
                                    Consumer.from(GROUP, consumerName),
                                    StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                            );
                    
                    if (messages != null && !messages.isEmpty()) {
                        for (MapRecord<String, Object, Object> record : messages) {
                            String sessionId = (String) record.getValue().get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
                            RecordId recordId = record.getId();
                            
                            // 提交到线程池异步处理，处理完成后再 ACK
                            executor.submit(() -> {
                                try {
                                    processInterviewEvaluation(sessionId);
                                    // 只有处理成功才 ACK
                                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);
                                } catch (Exception e) {
                                    log.error("❌ 面试评估任务异常，消息将保留在 PEL 中等待恢复，sessionId: {}", sessionId, e);
                                }
                            });
                        }
                    }
                } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                    log.warn("⚠️ Redis 连接断开，5秒后重试... {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    log.error("❌ 监听 Redis Stream 异常", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // 每 15 轮（约 30 秒）扫描一次 PEL，恢复异常遗留的消息
                if (++loopCount % 15 == 0) {
                    pendingRecoverer.recover(STREAM_KEY, GROUP, consumerName, executor, fields -> {
                        Object sessionId = fields.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
                        if (sessionId != null) {
                            processInterviewEvaluation(sessionId.toString());
                        }
                    });
                }
            }
            log.info("🛑 面试评估监听线程已退出");
        }, "interview-evaluation-listener");
        
        // 设置为守护线程
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("✅ 面试评估监听线程已启动: {}, 守护: {}", listenerThread.getName(), listenerThread.isDaemon());
    }

    /**
     * 应用关闭时优雅关闭
     */
    @PreDestroy
    public void shutdown() {
        log.info("🛑 开始关闭面试评估消费者...");

        // 1. 中断监听线程
        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt();
            log.info("✅ 监听线程已中断");
        }

        // 2. 关闭线程池
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("⚠️ 线程池未能在60秒内完成所有任务，强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("❌ 线程池强制关闭失败");
                }
            }
            log.info("✅ 面试评估线程池已关闭");
        } catch (InterruptedException e) {
            log.error("❌ 关闭线程池时被中断", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理面试评估
     */
    private void processInterviewEvaluation(String sessionId) {
        log.info("🚀 开始处理面试评估, sessionId: {}", sessionId);
        
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            // 与简历分析消费端同一修复：不能静默 return（上层会 ACK 丢消息），
            // 抛异常让消息留在 PEL，由 StreamPendingRecoverer 重投，超限后死信。
            throw new IllegalStateException("面试会话不存在，消息留在 PEL 等待重试: " + sessionId);
        }

        try {
            // 更新状态为 PROCESSING
            session.setEvaluateStatus(EvaluateStatus.PROCESSING.getCode());
            sessionMapper.updateById(session);
            log.info("🔄 评估状态已更新为 PROCESSING");

            // 1. 获取所有问题和答案
            List<InterviewQuestion> questions = questionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, sessionId)
                            .orderByAsc(InterviewQuestion::getQuestionIndex)
            );
            
            List<InterviewAnswer> answers = answerMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewAnswer>()
                            .eq(InterviewAnswer::getSessionId, sessionId)
                            .orderByAsc(InterviewAnswer::getQuestionIndex)
            );
            
            // 详细日志：记录查询结果
            log.info("📊 查询结果 - 问题数: {}, 答案数: {}", questions.size(), answers.size());
            
            if (questions.isEmpty()) {
                log.error("❌ 会话 {} 没有面试问题记录", sessionId);
                throw new BusinessException("NO_QUESTIONS_FOUND", "会话没有面试问题，无法评估");
            }
            
            // 处理白卷情况：生成零分评估报告
            if (answers.isEmpty()) {
                log.warn("⚠️ 会话 {} 用户未回答任何问题，生成零分评估报告", sessionId);
                generateEmptyEvaluation(session, questions);
                return;  // 直接返回，不执行后续 AI 评估
            }

            // 2. 优先使用统一评估服务（分批评估 + 结构化输出重试 + 二次汇总）
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

            // 3. 降级：简易评估（单次 AI 调用 + 手写 prompt）
            generateEvaluationLegacy(session, questions, answers);
            log.info("✅ 评估报告生成完成（简易评估）, sessionId: {}", sessionId);

        } catch (Exception e) {
            log.error("❌ 生成评估报告失败, sessionId: {}", sessionId, e);
            session.setEvaluateStatus(EvaluateStatus.FAILED.getCode());
            session.setEvaluateError(e.getMessage());
            sessionMapper.updateById(session);
        }
    }

    /**
     * 将问题与答案按 questionIndex 对齐为统一评估服务的问答记录。
     * 未作答的问题保留（userAnswer = null），由评估服务按 0 分处理。
     */
    private List<QaRecord> buildQaRecords(List<InterviewQuestion> questions, List<InterviewAnswer> answers) {
        Map<Integer, InterviewAnswer> answerMap = new java.util.HashMap<>();
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
     * 将统一评估服务的报告写回会话与答案表。
     */
    private void applyEvaluationReport(InterviewSession session, List<InterviewAnswer> answers,
                                       EvaluationReport report) {
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

        // 按题目更新答案评分与反馈
        Map<Integer, InterviewAnswer> answerMap = new java.util.HashMap<>();
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
            Map<Integer, InterviewQuestion> questionMap = new java.util.HashMap<>();
            for (InterviewQuestion q : questionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, session.getSessionId()))) {
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

        session.setEvaluateStatus(EvaluateStatus.COMPLETED.getCode());
        session.setStatus(SessionStatus.EVALUATED.getCode());
        session.setEvaluatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    /**
     * 简易评估（原实现，作为统一评估服务失败时的降级方案）
     */
    private void generateEvaluationLegacy(InterviewSession session, List<InterviewQuestion> questions,
                                          List<InterviewAnswer> answers) throws Exception {
        String sessionId = session.getSessionId();
        // 构建评估请求
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

        // 调用 AI 评估
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

        // 更新每个问题的评分
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questionEvaluations = (List<Map<String, Object>>) result.get("questionEvaluations");

        if (questionEvaluations != null) {
            for (Map<String, Object> eval : questionEvaluations) {
                int qIndex = JsonUtils.safeGetInt(eval.get("questionIndex"));
                double score = JsonUtils.safeGetDouble(eval.get("score"));
                String feedback = (String) eval.get("feedback");
                
                // 更新答案表
                InterviewAnswer answer = answerMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewAnswer>()
                                .eq(InterviewAnswer::getSessionId, sessionId)
                                .eq(InterviewAnswer::getQuestionIndex, qIndex)
                );
                
                if (answer != null) {
                    answer.setScore((int) score);
                    answer.setFeedback(feedback);
                    answer.setEvaluatedAt(LocalDateTime.now());
                    answerMapper.updateById(answer);
                }
            }
        }
        
        session.setEvaluateStatus(EvaluateStatus.COMPLETED.getCode());
        session.setStatus(SessionStatus.EVALUATED.getCode());
        session.setEvaluatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    /**
     * 生成白卷评估报告（零分）
     */
    private void generateEmptyEvaluation(InterviewSession session, List<InterviewQuestion> questions) {
        try {
            // 设置零分
            session.setOverallScore(0);
            session.setOverallFeedback("候选人未回答任何问题，无法进行有效评估。建议认真准备面试，积极回答问题以展示自己的能力。");

            // 设置优点为空
            session.setStrengthsJson(MAPPER.writeValueAsString(List.of()));

            // 设置改进建议
            List<String> improvements = List.of(
                "面试中应积极回答问题，即使不确定也要尝试表达自己的思路",
                "建议提前准备常见面试题，增强自信心",
                "遇到不会的问题可以请求提示或换个角度思考",
                "保持冷静，不要因为紧张而放弃回答"
            );
            session.setImprovementsJson(MAPPER.writeValueAsString(improvements));
            
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
            
            // 更新会话状态
            session.setEvaluateStatus(EvaluateStatus.COMPLETED.getCode());
            session.setStatus(SessionStatus.EVALUATED.getCode());
            session.setEvaluatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            
            log.info("✅ 白卷评估报告生成完成, sessionId: {}, 总分: 0", session.getSessionId());
            
        } catch (Exception e) {
            log.error("❌ 生成白卷评估报告失败", e);
            throw new BusinessException("EMPTY_EVALUATION_FAILED", "生成白卷评估报告失败: " + e.getMessage());
        }
    }
}

package com.edu.muc.app.modules.interview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.infrastructure.redis.RedisStreamProducer;
import com.edu.muc.app.modules.interview.domain.InterviewAnswer;
import com.edu.muc.app.modules.interview.domain.InterviewQuestion;
import com.edu.muc.app.modules.interview.domain.InterviewSession;
import com.edu.muc.app.modules.interview.dto.*;
import com.edu.muc.app.modules.interview.enums.EvaluateStatus;
import com.edu.muc.app.modules.interview.enums.SessionStatus;
import com.edu.muc.app.modules.interview.mapper.InterviewAnswerMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewQuestionMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewSessionMapper;
import com.edu.muc.app.modules.interview.service.InterviewService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    private static final int DEFAULT_FOLLOW_UP_COUNT = 2;
    
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ChatClient chatClient;
    private final RedisStreamProducer streamProducer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        log.info("🎯 创建面试会话: skillId={}, difficulty={}, questionCount={}", 
                request.getSkillId(), request.getDifficulty(), request.getQuestionCount());

        // 1. 检查是否有未完成的会话
        if (request.getResumeId() != null && !Boolean.TRUE.equals(request.getForceCreate())) {
            InterviewSession existing = sessionMapper.selectOne(
                    new LambdaQueryWrapper<InterviewSession>()
                            .eq(InterviewSession::getResumeId, request.getResumeId())
                            .in(InterviewSession::getStatus, 
                                SessionStatus.CREATED.getCode(), 
                                SessionStatus.IN_PROGRESS.getCode())
                            .orderByDesc(InterviewSession::getCreatedAt)
                            .last("LIMIT 1")
            );
            
            if (existing != null) {
                log.info("✅ 找到未完成会话: {}", existing.getSessionId());
                return convertToDTO(existing);
            }
        }

        // 2. 生成 sessionId
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        // 3. AI 生成问题
        List<InterviewQuestionDTO> questions = generateQuestions(request);
        log.info("✅ 生成了 {} 个面试问题", questions.size());

        // 4. 保存会话
        InterviewSession session = new InterviewSession();
        session.setSessionId(sessionId);
        session.setResumeId(request.getResumeId());
        session.setResumeText(request.getResumeText());
        session.setJdText(request.getJdText());
        session.setSkillId(request.getSkillId());
        session.setDifficulty(request.getDifficulty());
        session.setTotalQuestions(request.getQuestionCount());
        session.setCurrentQuestionIndex(0);
        session.setStatus(SessionStatus.IN_PROGRESS.getCode());
        session.setEvaluateStatus(EvaluateStatus.PENDING.getCode());
        session.setCreatedAt(LocalDateTime.now());
        
        sessionMapper.insert(session);
        
        // 5. 保存问题到数据库
        saveQuestionsToDatabase(sessionId, questions);
        log.info("✅ 问题已保存到数据库");
        
        log.info("✅ 会话创建成功: {}", sessionId);

        return convertToDTO(session);
    }

    /**
     * AI 生成面试问题
     */
    private List<InterviewQuestionDTO> generateQuestions(CreateInterviewRequest request) {
        try {
            // 动态注入当前时间，解决大模型时间幻觉问题
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年M月");
            String currentTime = LocalDateTime.now().format(formatter);

            // 加载提示词模板
            var systemTemplate = new org.springframework.ai.chat.prompt.PromptTemplate(
                    new DefaultResourceLoader().getResource("classpath:prompts/interview-question-skill-system.st")
            );
            var userTemplate = new org.springframework.ai.chat.prompt.PromptTemplate(
                    new DefaultResourceLoader().getResource("classpath:prompts/interview-question-skill-user.st")
            );

            // 构建历史知识点（空，因为是第一次）
            String historicalSection = "无";
            
            // 构建问题分布表（简化版）
            String allocationTable = String.format(
                    "| 技术核心 | %d | 基础知识与原理 |\n" +
                    "| 项目经验 | %d | 实战应用能力 |\n" +
                    "| 系统设计 | %d | 架构思维 |",
                    (int) Math.ceil(request.getQuestionCount() * 0.5),
                    (int) Math.ceil(request.getQuestionCount() * 0.3),
                    request.getQuestionCount() - (int) Math.ceil(request.getQuestionCount() * 0.5) - (int) Math.ceil(request.getQuestionCount() * 0.3)
            );
            
            // 构建参考题库（空）
            String referenceSection = "无";
            
            // 构建 JD 部分
            String jdSection = request.getJdText() != null && !request.getJdText().isEmpty() 
                    ? request.getJdText() 
                    : "无特定职位要求";
            
            // 构建 Skill Tool 指令
            String skillToolCommand = String.format("读取技能：%s", request.getSkillId());

            // 渲染提示词 - 使用 HashMap 因为 Map.of() 最多支持10个参数
            Map<String, Object> systemVars = new HashMap<>();
            systemVars.put("skillName", getSkillName(request.getSkillId()));
            systemVars.put("skillDescription", getSkillDescription(request.getSkillId()));
            systemVars.put("difficultyDescription", getDifficultyDescription(request.getDifficulty()));
            systemVars.put("questionCount", request.getQuestionCount());
            systemVars.put("followUpCount", DEFAULT_FOLLOW_UP_COUNT);
            systemVars.put("allocationTable", allocationTable);
            systemVars.put("skillToolCommand", skillToolCommand);
            
            String systemPrompt = systemTemplate.render(systemVars);

            Map<String, Object> userVars = new HashMap<>();
            userVars.put("resumeText", request.getResumeText() != null ? request.getResumeText() : "");
            userVars.put("jdText", request.getJdText() != null ? request.getJdText() : "");
            userVars.put("historicalSection", historicalSection);
            userVars.put("questionCount", request.getQuestionCount());
            userVars.put("followUpCount", DEFAULT_FOLLOW_UP_COUNT);
            userVars.put("difficultyDescription", getDifficultyDescription(request.getDifficulty()));
            userVars.put("skillName", getSkillName(request.getSkillId()));
            userVars.put("skillDescription", getSkillDescription(request.getSkillId()));
            userVars.put("skillToolCommand", skillToolCommand);
            userVars.put("allocationTable", allocationTable);
            userVars.put("referenceSection", referenceSection);
            userVars.put("jdSection", jdSection);
            
            String userPrompt = userTemplate.render(userVars);

            log.info("🤖 调用 AI 生成问题...");

            // 调用 AI
            String aiResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            log.info("✅ AI 返回结果长度: {}", aiResponse.length());

            // 解析 JSON 响应
            return parseQuestionsFromAI(aiResponse, request.getQuestionCount());

        } catch (Exception e) {
            log.error("❌ AI 生成问题失败", e);
            // 降级方案：返回预设问题
            return generateFallbackQuestions(request);
        }
    }

    /**
     * 从 AI 响应中解析问题
     */
    private List<InterviewQuestionDTO> parseQuestionsFromAI(String aiResponse, int expectedCount) {
        try {
            log.info("🔍 AI 原始响应长度: {}", aiResponse.length());
            log.debug("🔍 AI 原始响应内容: {}", aiResponse);
            
            // 尝试提取 JSON 部分
            String jsonStr = JsonUtils.extractJson(aiResponse);
            log.info("✅ 提取的 JSON 字符串长度: {}", jsonStr.length());
            log.debug("✅ 提取的 JSON: {}", jsonStr);

            Map<String, Object> result = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            log.info("✅ JSON 解析成功，keys: {}", result.keySet());
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questionsData = (List<Map<String, Object>>) result.get("questions");
            
            if (questionsData == null || questionsData.isEmpty()) {
                log.error("❌ AI 返回的 questions 数组为空或不存在");
                throw new RuntimeException("AI 未返回有效的问题列表");
            }
            
            log.info("✅ 找到 {} 个问题数据", questionsData.size());
            
            List<InterviewQuestionDTO> questions = new ArrayList<>();
            for (int i = 0; i < Math.min(expectedCount, questionsData.size()); i++) {
                Map<String, Object> qData = questionsData.get(i);
                
                if (qData == null) {
                    log.warn("⚠️ 问题 {} 的数据为 null，跳过", i);
                    continue;
                }
                
                InterviewQuestionDTO question = new InterviewQuestionDTO();
                question.setQuestionIndex(i);
                question.setQuestion((String) qData.get("question"));
                question.setType((String) qData.getOrDefault("type", "technical"));
                question.setCategory((String) qData.getOrDefault("category", "general"));
                question.setUserAnswer(null);
                question.setScore(null);
                question.setFeedback(null);
                
                if (question.getQuestion() == null || question.getQuestion().isEmpty()) {
                    log.warn("⚠️ 问题 {} 的内容为空，跳过", i);
                    continue;
                }
                
                questions.add(question);
            }
            
            if (questions.isEmpty()) {
                log.error("❌ 解析后没有有效的问题");
                throw new RuntimeException("AI 返回的问题格式不正确");
            }
            
            log.info("✅ 成功解析 {} 个问题", questions.size());
            return questions;
        } catch (Exception e) {
            log.error("❌ 解析 AI 响应失败", e);
            log.error("❌ AI 原始响应: {}", aiResponse);
            throw new RuntimeException("解析问题失败: " + e.getMessage(), e);
        }
    }

    /**
     * 降级方案：生成预设问题
     */
    private List<InterviewQuestionDTO> generateFallbackQuestions(CreateInterviewRequest request) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        
        String[] defaultQuestions = {
            "请介绍一下你的技术背景和专业技能",
            "你在项目中遇到的最大挑战是什么？如何解决的？",
            "请描述一个你最近完成的项目，你的角色和贡献是什么？",
            "你如何保持技术更新和学习新技术？",
            "请谈谈你对团队协作的理解和经验"
        };
        
        for (int i = 0; i < Math.min(request.getQuestionCount(), defaultQuestions.length); i++) {
            InterviewQuestionDTO question = new InterviewQuestionDTO();
            question.setQuestionIndex(i);
            question.setQuestion(defaultQuestions[i]);
            question.setType("technical");
            question.setCategory("general");
            question.setUserAnswer(null);
            question.setScore(null);
            question.setFeedback(null);
            questions.add(question);
        }
        
        return questions;
    }

    @Override
    public InterviewSessionDTO getSession(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        return convertToDTO(session);
    }

    @Override
    public CurrentQuestionResponse getCurrentQuestion(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }

        if ("COMPLETED".equals(session.getStatus()) || SessionStatus.EVALUATED.getCode().equals(session.getStatus())) {
            CurrentQuestionResponse response = new CurrentQuestionResponse();
            response.setCompleted(true);
            response.setMessage("面试已完成");
            return response;
        }

        // 从数据库获取当前问题
        InterviewQuestionDTO question = getQuestionFromDatabase(sessionId, session.getCurrentQuestionIndex());
        
        if (question == null) {
            throw new BusinessException("QUESTION_NOT_FOUND", "问题不存在");
        }
        
        CurrentQuestionResponse response = new CurrentQuestionResponse();
        response.setCompleted(false);
        response.setQuestion(question);
        
        return response;
    }

    @Override
    @Transactional
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        InterviewSession session = sessionMapper.selectById(request.getSessionId());
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }

        // 保存答案到数据库
        saveAnswerToDatabase(request.getSessionId(), request.getQuestionIndex(), request.getAnswer());
        
        // 更新当前题目索引
        int nextIndex = request.getQuestionIndex() + 1;
        session.setCurrentQuestionIndex(nextIndex);
        
        // 检查是否完成
        boolean hasNext = nextIndex < session.getTotalQuestions();
        if (!hasNext) {
            session.setStatus(SessionStatus.COMPLETED.getCode());
            session.setCompletedAt(LocalDateTime.now());
        }
        
        sessionMapper.updateById(session);

        SubmitAnswerResponse response = new SubmitAnswerResponse();
        response.setHasNextQuestion(hasNext);
        response.setCurrentIndex(nextIndex);
        response.setTotalQuestions(session.getTotalQuestions());
        
        if (hasNext) {
            // 从数据库获取下一题
            InterviewQuestionDTO nextQuestion = getQuestionFromDatabase(request.getSessionId(), nextIndex);
            response.setNextQuestion(nextQuestion);
        }
        
        return response;
    }

    @Override
    @Transactional
    public void saveAnswer(SubmitAnswerRequest request) {
        // 保存答案但不推进索引
        saveAnswerToDatabase(request.getSessionId(), request.getQuestionIndex(), request.getAnswer());
        log.info("💾 暂存答案: sessionId={}, questionIndex={}", 
                request.getSessionId(), request.getQuestionIndex());
    }

    @Override
    public InterviewReportDTO getReport(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }

        // 如果还未评估或评估失败，触发生成
        String evaluateStatus = session.getEvaluateStatus();

        // 先检查是否正在处理中（防止重复触发）
        if (EvaluateStatus.PROCESSING.getCode().equals(evaluateStatus)) {
            log.info("⏳ 评估正在处理中，请稍后刷新: {}", sessionId);
            throw new BusinessException("EVALUATION_IN_PROGRESS", "评估正在处理中，请稍后刷新");
        }

        if (evaluateStatus == null ||
            EvaluateStatus.PENDING.getCode().equals(evaluateStatus) ||
            EvaluateStatus.FAILED.getCode().equals(evaluateStatus)) {
            
            // 异步触发评估
            try {
                streamProducer.sendInterviewEvaluationTask(sessionId);
                log.info("📤 已发送评估任务到 Redis Stream: {}", sessionId);
                session.setEvaluateStatus(EvaluateStatus.PENDING.getCode());
                sessionMapper.updateById(session);
            } catch (Exception e) {
                log.error("❌ 发送评估任务失败", e);
                throw new BusinessException("EVALUATION_TRIGGER_FAILED", "触发评估失败: " + e.getMessage());
            }
            
            // 重新加载
            session = sessionMapper.selectById(sessionId);
            
            // 如果还是 PENDING，说明消费者还没开始处理
            if (EvaluateStatus.PENDING.getCode().equals(session.getEvaluateStatus())) {
                throw new BusinessException("EVALUATION_PENDING", "评估任务已提交，请稍后刷新查看结果");
            }
        }

        // 构建报告
        InterviewReportDTO report = new InterviewReportDTO();
        report.setSessionId(sessionId);
        report.setTotalQuestions(session.getTotalQuestions());
        report.setOverallScore(session.getOverallScore() != null ? session.getOverallScore() : 0);
        report.setOverallFeedback(session.getOverallFeedback());
        
        // 解析 strengths 和 improvements
        try {
            if (session.getStrengthsJson() != null) {
                report.setStrengths(objectMapper.readValue(
                        session.getStrengthsJson(), 
                        new TypeReference<List<String>>() {}
                ));
            }
            if (session.getImprovementsJson() != null) {
                report.setImprovements(objectMapper.readValue(
                        session.getImprovementsJson(), 
                        new TypeReference<List<String>>() {}
                ));
            }
        } catch (Exception e) {
            log.error("解析评估结果失败", e);
            report.setStrengths(List.of());
            report.setImprovements(List.of());
        }
        
        // 从数据库加载问题详情
        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .orderByAsc(InterviewQuestion::getQuestionIndex)
        );
        
        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .orderByAsc(InterviewAnswer::getQuestionIndex)
        );
        
        // 构建问题评估列表
        List<InterviewReportDTO.QuestionEvaluationDTO> questionDetails = new ArrayList<>();
        Map<Integer, InterviewAnswer> answerMap = answers.stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionIndex, a -> a));
        
        for (InterviewQuestion q : questions) {
            InterviewAnswer a = answerMap.get(q.getQuestionIndex());
            
            InterviewReportDTO.QuestionEvaluationDTO detail = new InterviewReportDTO.QuestionEvaluationDTO();
            detail.setQuestionIndex(q.getQuestionIndex());
            detail.setQuestion(q.getQuestion());
            detail.setCategory(q.getCategory());
            detail.setUserAnswer(a != null ? a.getAnswer() : "");
            detail.setScore(a != null && a.getScore() != null ? a.getScore() : 0);
            detail.setFeedback(a != null ? a.getFeedback() : "");
            
            questionDetails.add(detail);
        }
        
        report.setQuestionDetails(questionDetails);
        
        // 计算类别分数
        Map<String, List<InterviewReportDTO.QuestionEvaluationDTO>> categoryMap = questionDetails.stream()
                .collect(Collectors.groupingBy(InterviewReportDTO.QuestionEvaluationDTO::getCategory));
        
        List<InterviewReportDTO.CategoryScoreDTO> categoryScores = categoryMap.entrySet().stream()
                .map(entry -> {
                    InterviewReportDTO.CategoryScoreDTO cs = new InterviewReportDTO.CategoryScoreDTO();
                    cs.setCategory(entry.getKey());
                    cs.setQuestionCount(entry.getValue().size());
                    double avgScore = entry.getValue().stream()
                            .mapToDouble(InterviewReportDTO.QuestionEvaluationDTO::getScore)
                            .average()
                            .orElse(0);
                    cs.setScore(avgScore);
                    return cs;
                })
                .collect(Collectors.toList());
        
        report.setCategoryScores(categoryScores);
        
        // 构建参考答案（如果有）
        List<InterviewReportDTO.ReferenceAnswerDTO> referenceAnswers = questions.stream()
                .map(q -> {
                    InterviewReportDTO.ReferenceAnswerDTO ref = new InterviewReportDTO.ReferenceAnswerDTO();
                    ref.setQuestionIndex(q.getQuestionIndex());
                    ref.setQuestion(q.getQuestion());
                    ref.setReferenceAnswer(q.getReferenceAnswer());
                    
                    try {
                        if (q.getKeyPointsJson() != null) {
                            ref.setKeyPoints(objectMapper.readValue(
                                    q.getKeyPointsJson(),
                                    new TypeReference<List<String>>() {}
                            ));
                        }
                    } catch (Exception e) {
                        ref.setKeyPoints(List.of());
                    }
                    
                    return ref;
                })
                .collect(Collectors.toList());
        
        report.setReferenceAnswers(referenceAnswers);
        
        return report;
    }

    /**
     * AI 生成评估报告
     */
    private void generateEvaluation(InterviewSession session) {
        try {
            log.info("🤖 开始生成评估报告: {}", session.getSessionId());
            
            session.setEvaluateStatus(EvaluateStatus.PROCESSING.getCode());
            sessionMapper.updateById(session);

            // 1. 获取所有问题和答案
            List<InterviewQuestion> questions = questionMapper.selectList(
                    new LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, session.getSessionId())
                            .orderByAsc(InterviewQuestion::getQuestionIndex)
            );
            
            List<InterviewAnswer> answers = answerMapper.selectList(
                    new LambdaQueryWrapper<InterviewAnswer>()
                            .eq(InterviewAnswer::getSessionId, session.getSessionId())
                            .orderByAsc(InterviewAnswer::getQuestionIndex)
            );
            
            if (questions.isEmpty()) {
                throw new BusinessException("NO_QUESTIONS_FOUND", "没有可评估的问题");
            }
            if (answers.isEmpty()) {
                throw new BusinessException("NO_ANSWERS_FOUND", "没有可评估的答案");
            }

            // 2. 构建评估请求
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

            // 3. 调用 AI 评估
            log.info("🤖 调用 AI 进行评估...");
            String aiResponse = chatClient.prompt()
                    .system("你是一位资深技术面试官，请客观、专业地评估候选人的回答。")
                    .user(evaluationPrompt.toString())
                    .call()
                    .content();
            
            log.info("✅ AI 评估完成，响应长度: {}", aiResponse.length());

            // 4. 解析评估结果
            String jsonStr = JsonUtils.extractJson(aiResponse);
            Map<String, Object> result = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            
            // 5. 更新会话（安全类型转换，兼容 AI 返回的 Double 类型）
            Object scoreObj = result.getOrDefault("overallScore", 0);
            session.setOverallScore(scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 0);
            session.setOverallFeedback((String) result.get("overallFeedback"));
            
            @SuppressWarnings("unchecked")
            List<String> strengths = (List<String>) result.get("strengths");
            session.setStrengthsJson(objectMapper.writeValueAsString(strengths != null ? strengths : List.of()));
            
            @SuppressWarnings("unchecked")
            List<String> improvements = (List<String>) result.get("improvements");
            session.setImprovementsJson(objectMapper.writeValueAsString(improvements != null ? improvements : List.of()));
            
            // 6. 更新每个问题的评分
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questionEvaluations = (List<Map<String, Object>>) result.get("questionEvaluations");
            
            if (questionEvaluations != null) {
                for (Map<String, Object> eval : questionEvaluations) {
                    // 安全类型转换，兼容 AI 返回的多种类型
                    int qIndex = JsonUtils.safeGetInt(eval.get("questionIndex"));
                    double score = JsonUtils.safeGetDouble(eval.get("score"));
                    String feedback = (String) eval.get("feedback");

                    // 更新答案表
                    InterviewAnswer answer = answerMapper.selectOne(
                            new LambdaQueryWrapper<InterviewAnswer>()
                                    .eq(InterviewAnswer::getSessionId, session.getSessionId())
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
            
            log.info("✅ 评估报告生成完成");

        } catch (Exception e) {
            log.error("❌ 生成评估报告失败", e);
            session.setEvaluateStatus(EvaluateStatus.FAILED.getCode());
            session.setEvaluateError(e.getMessage());
            sessionMapper.updateById(session);
        }
    }

    @Override
    public List<TextSessionMetaDTO> listSessions() {
        List<InterviewSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<InterviewSession>()
                        .orderByDesc(InterviewSession::getCreatedAt)
        );
        
        return sessions.stream()
                .map(this::convertToMetaDTO)
                .collect(Collectors.toList());
    }

    @Override
    public InterviewSessionDTO findUnfinishedSession(Long resumeId) {
        InterviewSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getResumeId, resumeId)
                        .in(InterviewSession::getStatus, "CREATED", "IN_PROGRESS")
                        .orderByDesc(InterviewSession::getCreatedAt)
                        .last("LIMIT 1")
        );
        
        return session != null ? convertToDTO(session) : null;
    }

    @Override
    @Transactional
    public void completeInterview(String sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        
        session.setStatus(SessionStatus.COMPLETED.getCode());
        session.setCompletedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        
        log.info("✅ 面试提前交卷: {}", sessionId);
        
        // 异步触发评估（通过 Redis Stream）
        try {
            streamProducer.sendInterviewEvaluationTask(sessionId);
            log.info("📤 已发送评估任务到 Redis Stream: {}", sessionId);
        } catch (Exception e) {
            log.error("❌ 发送评估任务失败", e);
            // 即使发送失败，也不影响交卷操作
        }
    }

    // ========== 辅助方法 ==========

    private InterviewSessionDTO convertToDTO(InterviewSession session) {
        InterviewSessionDTO dto = new InterviewSessionDTO();
        dto.setSessionId(session.getSessionId());
        dto.setResumeText(session.getResumeText());
        dto.setTotalQuestions(session.getTotalQuestions());
        dto.setCurrentQuestionIndex(session.getCurrentQuestionIndex());
        dto.setStatus(session.getStatus());

        // 批量查询所有问题和答案，避免 N+1
        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, session.getSessionId())
                        .orderByAsc(InterviewQuestion::getQuestionIndex)
        );

        List<InterviewAnswer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, session.getSessionId())
        );
        Map<Integer, InterviewAnswer> answerMap = answers.stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionIndex, a -> a));

        List<InterviewSessionDTO.InterviewQuestionDTO> questionDTOs = questions.stream()
                .map(q -> {
                    InterviewSessionDTO.InterviewQuestionDTO internalDto = new InterviewSessionDTO.InterviewQuestionDTO();
                    internalDto.setQuestionIndex(q.getQuestionIndex());
                    internalDto.setQuestion(q.getQuestion());
                    internalDto.setType(q.getType());
                    internalDto.setCategory(q.getCategory());

                    InterviewAnswer answer = answerMap.get(q.getQuestionIndex());
                    if (answer != null) {
                        internalDto.setUserAnswer(answer.getAnswer());
                        internalDto.setScore(answer.getScore() != null ? answer.getScore().doubleValue() : null);
                        internalDto.setFeedback(answer.getFeedback());
                    }
                    return internalDto;
                })
                .collect(Collectors.toList());

        dto.setQuestions(questionDTOs);
        return dto;
    }

    private TextSessionMetaDTO convertToMetaDTO(InterviewSession session) {
        TextSessionMetaDTO dto = new TextSessionMetaDTO();
        dto.setSessionId(session.getSessionId());
        dto.setSkillId(session.getSkillId());
        dto.setDifficulty(session.getDifficulty());
        dto.setResumeId(session.getResumeId());
        dto.setTotalQuestions(session.getTotalQuestions());
        dto.setStatus(session.getStatus());
        dto.setEvaluateStatus(session.getEvaluateStatus());
        dto.setEvaluateError(session.getEvaluateError());
        dto.setOverallScore(session.getOverallScore());
        dto.setCreatedAt(session.getCreatedAt() != null ? 
                session.getCreatedAt().toString() : null);
        dto.setCompletedAt(session.getCompletedAt() != null ? 
                session.getCompletedAt().toString() : null);
        return dto;
    }

    private String getSkillName(String skillId) {
        // TODO: 从配置或数据库获取
        return switch (skillId) {
            case "java-backend" -> "Java后端开发";
            case "frontend" -> "前端开发";
            default -> "通用技术面试";
        };
    }

    private String getSkillDescription(String skillId) {
        return switch (skillId) {
            case "java-backend" -> "针对Java后端工程师的专项面试";
            case "frontend" -> "针对前端工程师的专项面试";
            default -> "通用技术能力面试";
        };
    }

    private String getDifficultyDescription(String difficulty) {
        return switch (difficulty) {
            case "junior" -> "校招/初级水平（0-1年经验）";
            case "mid" -> "中级水平（1-3年经验）";
            case "senior" -> "高级水平（3年以上经验）";
            default -> "中级水平";
        };
    }

    /**
     * 保存问题到数据库
     */
    private void saveQuestionsToDatabase(String sessionId, List<InterviewQuestionDTO> questions) {
        for (InterviewQuestionDTO dto : questions) {
            InterviewQuestion question = new InterviewQuestion();
            question.setSessionId(sessionId);
            question.setQuestionIndex(dto.getQuestionIndex());
            question.setQuestion(dto.getQuestion());
            question.setType(dto.getType());
            question.setCategory(dto.getCategory());
            question.setCreatedAt(LocalDateTime.now());
            
            questionMapper.insert(question);
        }
        log.info("✅ 已保存 {} 个问题到数据库", questions.size());
    }

    /**
     * 从数据库获取问题
     */
    private InterviewQuestionDTO getQuestionFromDatabase(String sessionId, int questionIndex) {
        InterviewQuestion question = questionMapper.selectOne(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .eq(InterviewQuestion::getQuestionIndex, questionIndex)
        );
        
        if (question == null) {
            return null;
        }
        
        InterviewQuestionDTO dto = new InterviewQuestionDTO();
        dto.setQuestionIndex(question.getQuestionIndex());
        dto.setQuestion(question.getQuestion());
        dto.setType(question.getType());
        dto.setCategory(question.getCategory());
        
        // 查询该问题的答案
        InterviewAnswer answer = answerMapper.selectOne(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .eq(InterviewAnswer::getQuestionIndex, questionIndex)
        );
        
        if (answer != null) {
            dto.setUserAnswer(answer.getAnswer());
            dto.setScore(answer.getScore() != null ? answer.getScore().doubleValue() : null);
            dto.setFeedback(answer.getFeedback());
        }
        
        return dto;
    }

    /**
     * 保存答案到数据库
     */
    private void saveAnswerToDatabase(String sessionId, int questionIndex, String answer) {
        // 检查是否已存在
        InterviewAnswer existing = answerMapper.selectOne(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getSessionId, sessionId)
                        .eq(InterviewAnswer::getQuestionIndex, questionIndex)
        );
        
        if (existing != null) {
            // 更新
            existing.setAnswer(answer);
            existing.setCreatedAt(LocalDateTime.now());
            answerMapper.updateById(existing);
        } else {
            // 插入
            InterviewAnswer newAnswer = new InterviewAnswer();
            newAnswer.setSessionId(sessionId);
            newAnswer.setQuestionIndex(questionIndex);
            newAnswer.setAnswer(answer);
            newAnswer.setCreatedAt(LocalDateTime.now());
            answerMapper.insert(newAnswer);
        }
        
        log.info("✅ 答案已保存: sessionId={}, questionIndex={}", sessionId, questionIndex);
    }

    @Override
    @Transactional
    public boolean deleteSession(String sessionId) {
        log.info("🗑️ 开始删除面试会话: {}", sessionId);
        
        try {
            // 1. 检查会话是否存在
            InterviewSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("⚠️ 会话不存在: {}", sessionId);
                return false;
            }
            
            log.info("📊 会话状态: {}, 评估状态: {}", session.getStatus(), session.getEvaluateStatus());
            
            // 2. 删除该会话的所有答案
            int deletedAnswers = answerMapper.delete(
                    new LambdaQueryWrapper<InterviewAnswer>()
                            .eq(InterviewAnswer::getSessionId, sessionId)
            );
            log.info("✅ 已删除 {} 个答案", deletedAnswers);
            
            // 3. 删除该会话的所有问题
            int deletedQuestions = questionMapper.delete(
                    new LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, sessionId)
            );
            log.info("✅ 已删除 {} 个问题", deletedQuestions);
            
            // 4. 删除会话本身
            int deletedSessions = sessionMapper.deleteById(sessionId);
            log.info("✅ 已删除 {} 个会话记录", deletedSessions);
            
            if (deletedSessions == 0) {
                log.error("❌ 会话删除失败，可能已被其他事务删除");
                return false;
            }
            
            log.info("✅ 面试会话删除成功: {}, 删除问题: {}, 答案: {}", 
                    sessionId, deletedQuestions, deletedAnswers);
            return true;
            
        } catch (Exception e) {
            log.error("❌ 删除面试会话失败: {}", sessionId, e);
            log.error("❌ 异常类型: {}", e.getClass().getName());
            log.error("❌ 异常消息: {}", e.getMessage());
            if (e.getCause() != null) {
                log.error("❌ 根本原因: {}", e.getCause().getMessage());
            }
            throw new RuntimeException("删除会话失败: " + e.getMessage(), e);
        }
    }
}

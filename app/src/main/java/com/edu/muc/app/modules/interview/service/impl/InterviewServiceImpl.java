package com.edu.muc.app.modules.interview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.ai.LlmProviderRegistry;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.modules.interview.listener.EvaluateStreamProducer;
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
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import com.edu.muc.app.modules.knowledgebase.service.impl.EmbeddingCacheService;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

    private static final int MAX_RESUME_TEXT_LENGTH = 6000;

    /**
     * 主问题追问次数（可通过环境变量 APP_INTERVIEW_FOLLOW_UP_COUNT 覆盖）
     */
    @Value("${app.interview.follow-up-count:2}")
    private int followUpCount;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ChatClient chatClient;
    private final EvaluateStreamProducer streamProducer;
    private final ResumesMapper resumesMapper;
    private final LlmProviderRegistry llmProviderRegistry;
    private final SmartRetrievalService smartRetrievalService;
    private final EmbeddingCacheService embeddingCacheService;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        if (request.getQuestionCount() < 1 || request.getQuestionCount() > 30) {
            throw new BusinessException("INVALID_QUESTION_COUNT", "题目数须在 1 至 30 之间");
        }
        log.info("🎯 创建面试会话: skillId={}, difficulty={}, questionCount={}",
                request.getSkillId(), request.getDifficulty(), request.getQuestionCount());

        // 0. 若只传了 resumeId 而未传简历文本，则从数据库回查简历内容，确保面试官能基于简历提问
        enrichResumeText(request);

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
        session.setKnowledgeBaseIds(serializeKnowledgeBaseIds(request.getKnowledgeBaseIds()));
        session.setSkillId(request.getSkillId());
        session.setDifficulty(request.getDifficulty());
        // 以实际生成的问题数为准（AI 可能少给题，避免答题时题目不存在）
        session.setTotalQuestions(questions.size());
        session.setCurrentQuestionIndex(0);
        session.setStatus(SessionStatus.IN_PROGRESS.getCode());
        session.setCreatedAt(LocalDateTime.now());
        
        sessionMapper.insert(session);
        
        // 5. 保存问题到数据库
        saveQuestionsToDatabase(sessionId, questions);
        log.info("✅ 问题已保存到数据库");
        
        log.info("✅ 会话创建成功: {}", sessionId);

        return convertToDTO(session);
    }

    /**
     * 若请求中只有 resumeId 而没有简历文本，则从数据库回查简历内容；
     * 同时截断过长的简历文本，避免超出模型上下文窗口。
     */
    private void enrichResumeText(CreateInterviewRequest request) {
        String resumeText = request.getResumeText();
        if ((resumeText == null || resumeText.isBlank()) && request.getResumeId() != null) {
            Resumes resume = resumesMapper.selectById(request.getResumeId());
            if (resume != null && resume.getResumeText() != null && !resume.getResumeText().isBlank()) {
                resumeText = resume.getResumeText();
                log.info("✅ 已根据 resumeId={} 回查简历内容，长度: {}", request.getResumeId(), resumeText.length());
            } else {
                log.warn("⚠️ 无法获取简历内容（简历不存在或尚未完成解析）: resumeId={}", request.getResumeId());
            }
        }
        if (resumeText != null && resumeText.length() > MAX_RESUME_TEXT_LENGTH) {
            log.info("✂️ 简历文本过长（{} 字），截断至 {} 字", resumeText.length(), MAX_RESUME_TEXT_LENGTH);
            resumeText = resumeText.substring(0, MAX_RESUME_TEXT_LENGTH);
        }
        request.setResumeText(resumeText);
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

            // 构建历史知识点（基于同一简历的历史面试题目 topicSummary 去重，避免重复出题）
            String historicalSection = buildHistoricalSection(request.getResumeId());
            
            // 构建问题分布表（简化版）
            String allocationTable = String.format(
                    "| 技术核心 | %d | 基础知识与原理 |\n" +
                    "| 项目经验 | %d | 实战应用能力 |\n" +
                    "| 系统设计 | %d | 架构思维 |",
                    (int) Math.ceil(request.getQuestionCount() * 0.5),
                    (int) Math.ceil(request.getQuestionCount() * 0.3),
                    request.getQuestionCount() - (int) Math.ceil(request.getQuestionCount() * 0.5) - (int) Math.ceil(request.getQuestionCount() * 0.3)
            );
            
            // 构建参考题库：若关联了知识库，则通过向量检索知识库内容作为出题参考（RAG 打通）
            String referenceSection = buildReferenceSection(request);
            
            // 构建 JD 部分
            String jdSection = request.getJdText() != null && !request.getJdText().isEmpty()
                    ? request.getJdText()
                    : "无特定职位要求";

            // 构建候选人简历部分（有简历时，面试官需结合简历中的项目与技能针对性提问）
            String resumeText = request.getResumeText();
            String resumeSection = (resumeText != null && !resumeText.isBlank())
                    ? "---简历内容开始---\n" + resumeText + "\n---简历内容结束---"
                    : "未提供简历，请依据面试方向与职位描述按通用标准提问。";
            
            // 构建 Skill Tool 指令
            String skillToolCommand = String.format("读取技能：%s", request.getSkillId());

            // 渲染提示词 - 使用 HashMap 因为 Map.of() 最多支持10个参数
            Map<String, Object> systemVars = new HashMap<>();
            systemVars.put("skillName", getSkillName(request.getSkillId()));
            systemVars.put("skillDescription", getSkillDescription(request.getSkillId()));
            systemVars.put("difficultyDescription", getDifficultyDescription(request.getDifficulty()));
            systemVars.put("questionCount", request.getQuestionCount());
            systemVars.put("followUpCount", followUpCount);
            systemVars.put("allocationTable", allocationTable);
            systemVars.put("skillToolCommand", skillToolCommand);
            
            String systemPrompt = systemTemplate.render(systemVars);

            Map<String, Object> userVars = new HashMap<>();
            userVars.put("resumeText", request.getResumeText() != null ? request.getResumeText() : "");
            userVars.put("jdText", request.getJdText() != null ? request.getJdText() : "");
            userVars.put("historicalSection", historicalSection);
            userVars.put("questionCount", request.getQuestionCount());
            userVars.put("followUpCount", followUpCount);
            userVars.put("difficultyDescription", getDifficultyDescription(request.getDifficulty()));
            userVars.put("skillName", getSkillName(request.getSkillId()));
            userVars.put("skillDescription", getSkillDescription(request.getSkillId()));
            userVars.put("skillToolCommand", skillToolCommand);
            userVars.put("allocationTable", allocationTable);
            userVars.put("referenceSection", referenceSection);
            userVars.put("jdSection", jdSection);
            userVars.put("resumeSection", resumeSection);

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
            Set<String> seenQuestions = new HashSet<>();
            for (int i = 0; i < questionsData.size() && questions.size() < expectedCount; i++) {
                Map<String, Object> qData = questionsData.get(i);
                
                if (qData == null) {
                    log.warn("⚠️ 问题 {} 的数据为 null，跳过", i);
                    continue;
                }
                
                InterviewQuestionDTO question = new InterviewQuestionDTO();
                if (!(qData.get("question") instanceof String text) || text.isBlank()
                        || !seenQuestions.add(text.strip())) {
                    continue;
                }
                question.setQuestionIndex(questions.size());
                question.setQuestion(text.strip());
                question.setType(qData.get("type") instanceof String type && !type.isBlank()
                        ? type.substring(0, Math.min(type.length(), 50)) : "technical");
                question.setCategory(qData.get("category") instanceof String category && !category.isBlank()
                        ? category.substring(0, Math.min(category.length(), 100)) : "general");
                // topicSummary：知识点摘要，用于历史去重（AI 未返回时置空，不影响出题）
                Object topicSummary = qData.get("topicSummary");
                if (topicSummary != null) {
                    String topic = topicSummary.toString().strip();
                    question.setTopicSummary(topic.substring(0, Math.min(topic.length(), 255)));
                }
                question.setUserAnswer(null);
                question.setScore(null);
                question.setFeedback(null);
                
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

    /**
     * RAG 打通：若请求指定了知识库，则向量化查询文本并从知识库检索相关片段，作为出题参考题库。
     * 检索失败时静默降级为"无参考题库"，不影响出题主流程。
     */
    private String buildReferenceSection(CreateInterviewRequest request) {
        List<Long> knowledgeBaseIds = request.getKnowledgeBaseIds();
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return "无";
        }
        try {
            String queryText = buildRagQueryText(request);
            if (queryText.isBlank()) {
                return "无";
            }
            float[] queryEmbedding = embeddingCacheService.embedCached(embeddingModel, queryText);
            String queryVector = JsonUtils.convertEmbeddingToJson(queryEmbedding);

            List<KnowledgeDocument> docs = smartRetrievalService.smartRetrieve(queryText, queryVector, knowledgeBaseIds);
            if (docs.isEmpty()) {
                log.info("📚 RAG 出题：知识库 {} 未检索到相关内容", knowledgeBaseIds);
                return "无";
            }
            String reference = docs.stream()
                    .map(doc -> "【" + (doc.getName() != null ? doc.getName() : "知识库资料") + "】\n" + doc.getContent())
                    .collect(Collectors.joining("\n\n"));
            log.info("📚 RAG 出题：从知识库 {} 检索到 {} 个片段作为出题参考", knowledgeBaseIds, docs.size());
            return reference;
        } catch (Exception e) {
            log.warn("📚 RAG 出题检索失败，降级为无参考题库: {}", e.getMessage());
            return "无";
        }
    }

    /**
     * 构建历史知识点摘要：查询同一简历下最近面试的问题 topicSummary 并去重，
     * 用于提示词中的"已考知识点"，避免历史面试重复出题。
     */
    private String buildHistoricalSection(Long resumeId) {
        if (resumeId == null) {
            return "无";
        }
        try {
            List<InterviewSession> historySessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<InterviewSession>()
                            .eq(InterviewSession::getResumeId, resumeId)
                            .orderByDesc(InterviewSession::getCreatedAt)
                            .last("LIMIT 5")
            );
            if (historySessions.isEmpty()) {
                return "无";
            }
            List<String> sessionIds = historySessions.stream()
                    .map(InterviewSession::getSessionId)
                    .toList();
            List<InterviewQuestion> historyQuestions = questionMapper.selectList(
                    new LambdaQueryWrapper<InterviewQuestion>()
                            .in(InterviewQuestion::getSessionId, sessionIds)
                            .isNotNull(InterviewQuestion::getTopicSummary)
                            .ne(InterviewQuestion::getTopicSummary, "")
                            .orderByAsc(InterviewQuestion::getCreatedAt)
            );
            List<String> topics = historyQuestions.stream()
                    .map(InterviewQuestion::getTopicSummary)
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .distinct()
                    .limit(20)
                    .toList();
            if (topics.isEmpty()) {
                return "无";
            }
            log.info("📚 历史知识点（topicSummary）: {}", topics);
            return String.join("；", topics);
        } catch (Exception e) {
            log.warn("查询历史知识点失败，降级为无: {}", e.getMessage());
            return "无";
        }
    }

    private String buildRagQueryText(CreateInterviewRequest request) {
        StringBuilder sb = new StringBuilder();
        String resumeText = request.getResumeText();
        if (resumeText != null && !resumeText.isBlank()) {
            sb.append(resumeText);
        }
        String jdText = request.getJdText();
        if (jdText != null && !jdText.isBlank()) {
            sb.append('\n').append(jdText);
        }
        if (sb.length() == 0) {
            sb.append(getSkillName(request.getSkillId()));
        }
        return sb.length() > 2000 ? sb.substring(0, 2000) : sb.toString();
    }

    private String serializeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(knowledgeBaseIds);
        } catch (JsonProcessingException e) {
            log.warn("序列化知识库 ID 失败: {}", e.getMessage());
            return null;
        }
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
        InterviewSession session = requireCurrentQuestion(request);
        int questionIndex = request.getQuestionIndex();
        if (request.getAnswer() == null || request.getAnswer().isBlank()) {
            throw new BusinessException("INVALID_ANSWER", "答案不能为空");
        }

        // 保存答案到数据库
        saveAnswerToDatabase(request.getSessionId(), questionIndex, request.getAnswer());
        
        // 更新当前题目索引
        int nextIndex = questionIndex + 1;
        session.setCurrentQuestionIndex(nextIndex);
        
        // 检查是否完成
        boolean hasNext = nextIndex < session.getTotalQuestions();
        if (!hasNext) {
            session.setStatus(SessionStatus.COMPLETED.getCode());
            session.setCompletedAt(LocalDateTime.now());
        }
        
        sessionMapper.updateById(session);
        if (!hasNext) {
            scheduleEvaluation(session.getSessionId());
        }

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
        requireCurrentQuestion(request);
        if (request.getAnswer() == null) {
            throw new BusinessException("INVALID_ANSWER", "请提供答案内容");
        }
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

        if (isAnswerable(session)) {
            throw new BusinessException("INTERVIEW_NOT_COMPLETED", "请先交卷再查看评估报告");
        }
        if (!EvaluateStatus.COMPLETED.getCode().equals(session.getEvaluateStatus())) {
            transactionTemplate.executeWithoutResult(ignored -> {
                InterviewSession current = lockSession(sessionId);
                String status = current.getEvaluateStatus();
                // 兼容升级前交卷后仍未入队的 PENDING 记录。
                boolean stalePending = EvaluateStatus.PENDING.getCode().equals(status)
                        && (current.getCompletedAt() == null
                        || current.getCompletedAt().isBefore(LocalDateTime.now().minusMinutes(5)));
                if (SessionStatus.COMPLETED.getCode().equals(current.getStatus())
                        && (status == null || EvaluateStatus.FAILED.getCode().equals(status) || stalePending)) {
                    scheduleEvaluation(sessionId);
                }
            });
            session = sessionMapper.selectById(sessionId);
            if (session == null) {
                throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
            }
            String evaluateStatus = session.getEvaluateStatus();
            if (EvaluateStatus.FAILED.getCode().equals(evaluateStatus)) {
                throw new BusinessException("EVALUATION_TRIGGER_FAILED", "评估任务提交失败，请稍后重试");
            }
            if (!EvaluateStatus.COMPLETED.getCode().equals(evaluateStatus)) {
                throw new BusinessException("EVALUATION_PENDING", "评估处理中，请稍后查看结果");
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
                .collect(Collectors.toMap(InterviewAnswer::getQuestionIndex, a -> a,
                        (left, right) -> left.getId() > right.getId() ? left : right));
        
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
        InterviewSession session = lockSession(sessionId);
        if (!isAnswerable(session)) {
            return;
        }
        
        session.setStatus(SessionStatus.COMPLETED.getCode());
        session.setCompletedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        
        log.info("✅ 面试提前交卷: {}", sessionId);
        
        scheduleEvaluation(sessionId);
    }

    private InterviewSession lockSession(String sessionId) {
        InterviewSession session = sessionMapper.selectOne(new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getSessionId, sessionId)
                .last("FOR UPDATE"));
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        return session;
    }

    private boolean isAnswerable(InterviewSession session) {
        return SessionStatus.IN_PROGRESS.getCode().equals(session.getStatus())
                || SessionStatus.CREATED.getCode().equals(session.getStatus());
    }

    private InterviewSession requireCurrentQuestion(SubmitAnswerRequest request) {
        InterviewSession session = lockSession(request.getSessionId());
        if (!isAnswerable(session)) {
            throw new BusinessException("INTERVIEW_ALREADY_COMPLETED", "面试已结束，无法提交答案");
        }
        Integer index = request.getQuestionIndex();
        if (index == null || index < 0 || index >= session.getTotalQuestions()
                || !index.equals(session.getCurrentQuestionIndex())) {
            throw new BusinessException("INTERVIEW_QUESTION_NOT_FOUND", "只能提交或暂存当前问题的答案");
        }
        return session;
    }

    private void scheduleEvaluation(String sessionId) {
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getSessionId, sessionId)
                .set(InterviewSession::getEvaluateStatus, EvaluateStatus.PENDING.getCode())
                .set(InterviewSession::getEvaluateError, null));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!streamProducer.send(sessionId)) {
                    TransactionTemplate failureTransaction =
                            new TransactionTemplate(transactionTemplate.getTransactionManager());
                    failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    failureTransaction.executeWithoutResult(ignored -> sessionMapper.update(null,
                            new LambdaUpdateWrapper<InterviewSession>()
                                    .eq(InterviewSession::getSessionId, sessionId)
                                    .eq(InterviewSession::getEvaluateStatus, EvaluateStatus.PENDING.getCode())
                                    .set(InterviewSession::getEvaluateStatus, EvaluateStatus.FAILED.getCode())
                                    .set(InterviewSession::getEvaluateError, "评估任务入队失败，请重试")));
                }
            }
        });
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
            question.setTopicSummary(dto.getTopicSummary());
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
            InterviewSession session = sessionMapper.selectOne(new LambdaQueryWrapper<InterviewSession>()
                    .eq(InterviewSession::getSessionId, sessionId)
                    .last("FOR UPDATE"));
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

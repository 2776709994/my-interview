package com.edu.muc.app.modules.voiceinterview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.ai.LlmProviderRegistry;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.common.exception.ErrorCode;
import com.edu.muc.app.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import com.edu.muc.app.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语音面试评估服务
 * <p>
 * 收集会话中的问答记录，调用 LLM 生成多维度评估报告并持久化。
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationMapper evaluationMapper;
    private final VoiceInterviewMessageMapper messageMapper;
    private final VoiceInterviewSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    // 加载评估提示词模板
    private static final String SYSTEM_PROMPT_TEMPLATE;
    private static final String USER_PROMPT_TEMPLATE;
    static {
        try {
            ClassPathResource sysResource = new ClassPathResource("prompts/voice-interview-evaluation-system.st");
            SYSTEM_PROMPT_TEMPLATE = StreamUtils.copyToString(
                    sysResource.getInputStream(), StandardCharsets.UTF_8);
            ClassPathResource userResource = new ClassPathResource("prompts/voice-interview-evaluation-user.st");
            USER_PROMPT_TEMPLATE = StreamUtils.copyToString(
                    userResource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("加载语音面试评估提示词模板失败", e);
        }
    }

    private record QaRecord(int questionIndex, String question, String category, String userAnswer) {
    }

    /**
     * 生成语音面试评估（由异步消费者调用）
     */
    public void generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            VoiceInterviewSessionEntity session = getSession(sessionId);
            List<VoiceInterviewMessageEntity> messages = messageMapper.selectList(
                    new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                            .eq(VoiceInterviewMessageEntity::getSessionId, sessionId)
                            .orderByAsc(VoiceInterviewMessageEntity::getSequenceNum)
            );

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                saveEmptyEvaluationTransactional(sessionId, session);
                return;
            }

            List<QaRecord> qaRecords = buildQaRecords(messages);

            String provider = session.getLlmProvider();
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            // 构建评估 Prompt
            StringBuilder qaSection = new StringBuilder();
            for (QaRecord record : qaRecords) {
                qaSection.append(String.format(
                        "问题 %d：%s\n类别：%s\n回答：%s\n\n",
                        record.questionIndex() + 1, record.question(), record.category(),
                        record.userAnswer() != null ? record.userAnswer() : "（未作答）"
                ));
            }
            String userPrompt = USER_PROMPT_TEMPLATE.replace("{qaRecords}", qaSection.toString());

            String aiResponse = chatClient.prompt()
                    .system(SYSTEM_PROMPT_TEMPLATE)
                    .user(userPrompt)
                    .call()
                    .content();

            log.info("语音面试 AI 评估完成，响应长度: {}", aiResponse.length());

            Map<String, Object> result = objectMapper.readValue(
                    JsonUtils.extractJson(aiResponse), new TypeReference<Map<String, Object>>() {});

            saveEvaluationTransactional(sessionId, session, result);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: " + e.getMessage());
        }
    }

    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = evaluationMapper.selectOne(
                new LambdaQueryWrapper<VoiceInterviewEvaluationEntity>()
                        .eq(VoiceInterviewEvaluationEntity::getSessionId, sessionId)
                        .last("LIMIT 1")
        );
        if (evaluation == null) {
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: " + sessionId);
        }

        return buildDetailDTO(evaluation);
    }

    /**
     * 从消息中构建问答记录（对齐源项目的配对逻辑）。
     */
    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;
        PendingQuestion pendingQuestion = null;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
            String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

            if (pendingQuestion != null && userText != null) {
                records.add(new QaRecord(index, pendingQuestion.question(), pendingQuestion.category(), userText));
                index++;
                pendingQuestion = null;
                if (aiText != null) {
                    pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
                }
                continue;
            }

            if (pendingQuestion != null) {
                records.add(new QaRecord(index, pendingQuestion.question(), pendingQuestion.category(), null));
                index++;
                pendingQuestion = null;
            }

            if (aiText != null && userText != null) {
                records.add(new QaRecord(index, aiText, inferCategory(aiText), userText));
                index++;
            } else if (aiText != null) {
                pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
            } else if (userText != null) {
                records.add(new QaRecord(index, "", "综合", userText));
                index++;
            }
        }

        if (pendingQuestion != null) {
            records.add(new QaRecord(index, pendingQuestion.question(), pendingQuestion.category(), null));
        }

        return records;
    }

    private record PendingQuestion(String question, String category) {}

    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    @Transactional
    public void saveEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session,
                                 Map<String, Object> result) {
        try {
            Object scoreObj = result.getOrDefault("overallScore", 0);
            int overallScore = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 0;

            @SuppressWarnings("unchecked")
            List<String> strengths = (List<String>) result.get("strengths");
            @SuppressWarnings("unchecked")
            List<String> improvements = (List<String>) result.get("improvements");

            List<Map<String, Object>> questionEvaluations = new ArrayList<>();
            Object qeObj = result.get("questionEvaluations");
            if (qeObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) map;
                        questionEvaluations.add(typed);
                    }
                }
            }

            VoiceInterviewEvaluationEntity entity = VoiceInterviewEvaluationEntity.builder()
                .sessionId(sessionId)
                .overallScore(overallScore)
                .overallFeedback((String) result.get("overallFeedback"))
                .questionEvaluationsJson(objectMapper.writeValueAsString(questionEvaluations))
                .strengthsJson(objectMapper.writeValueAsString(strengths != null ? strengths : List.of()))
                .improvementsJson(objectMapper.writeValueAsString(improvements != null ? improvements : List.of()))
                .referenceAnswersJson("[]")
                .interviewerRole(session.getRoleType())
                .interviewDate(session.getStartTime())
                .createdAt(LocalDateTime.now())
                .build();

            evaluationMapper.insert(entity);
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.getOverallScore());
        } catch (Exception e) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存评估失败: " + e.getMessage());
        }
    }

    @Transactional
    public void saveEmptyEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session) {
        try {
            VoiceInterviewEvaluationEntity entity = VoiceInterviewEvaluationEntity.builder()
                .sessionId(sessionId)
                .overallScore(0)
                .overallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。")
                .questionEvaluationsJson("[]")
                .strengthsJson("[]")
                .improvementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]")
                .referenceAnswersJson("[]")
                .interviewerRole(session.getRoleType())
                .interviewDate(session.getStartTime())
                .createdAt(LocalDateTime.now())
                .build();

            evaluationMapper.insert(entity);
            log.info("空评估结果已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存空评估失败: " + e.getMessage());
        }
    }

    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            List<Map<String, Object>> questionItems = objectMapper.readValue(
                entity.getQuestionEvaluationsJson(),
                new TypeReference<List<Map<String, Object>>>() {}
            );

            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson(),
                new TypeReference<List<String>>() {}
            );

            List<String> improvements = objectMapper.readValue(
                entity.getImprovementsJson(),
                new TypeReference<List<String>>() {}
            );

            List<AnswerDetail> answers = new ArrayList<>();
            for (int i = 0; i < questionItems.size(); i++) {
                Map<String, Object> q = questionItems.get(i);
                answers.add(AnswerDetail.builder()
                    .questionIndex(i)
                    .question(String.valueOf(q.getOrDefault("question", "")))
                    .category(String.valueOf(q.getOrDefault("category", "")))
                    .userAnswer(String.valueOf(q.getOrDefault("userAnswer", "")))
                    .score(JsonUtils.safeGetInt(q.get("score")))
                    .feedback(q.get("feedback") != null ? String.valueOf(q.get("feedback")) : null)
                    .referenceAnswer(null)
                    .keyPoints(null)
                    .build());
            }

            return VoiceEvaluationDetailDTO.builder()
                .sessionId(entity.getSessionId())
                .totalQuestions(answers.size())
                .overallScore(entity.getOverallScore() != null ? entity.getOverallScore() : 0)
                .overallFeedback(entity.getOverallFeedback())
                .strengths(strengths)
                .improvements(improvements)
                .answers(answers)
                .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: " + e.getMessage());
        }
    }

    private VoiceInterviewSessionEntity getSession(Long sessionId) {
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                "语音面试会话不存在: " + sessionId);
        }
        return session;
    }
}

package com.edu.muc.app.modules.voiceinterview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.common.exception.ErrorCode;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.modules.voiceinterview.config.VoiceInterviewProperties;
import com.edu.muc.app.modules.voiceinterview.dto.CreateSessionRequest;
import com.edu.muc.app.modules.voiceinterview.dto.SessionMetaDTO;
import com.edu.muc.app.modules.voiceinterview.dto.SessionResponseDTO;
import com.edu.muc.app.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import com.edu.muc.app.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.edu.muc.app.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语音面试服务
 * <p>
 * 会话生命周期管理、阶段流转、消息持久化与对话历史查询。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionMapper sessionMapper;
    private final VoiceInterviewMessageMapper messageMapper;
    private final VoiceInterviewEvaluationMapper evaluationMapper;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    private static final String DEFAULT_USER_ID = "default";

    /**
     * 创建新的语音面试会话
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : "java-backend";
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
            ? request.getLlmProvider()
            : null;

        LocalDateTime now = LocalDateTime.now();
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(DEFAULT_USER_ID)
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : "mid")
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                .startTime(now)
                .createdAt(now)
                .updatedAt(now)
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .build();

        sessionMapper.insert(session);

        log.info("Created voice interview session: {} with template: {}, phase: {}",
                session.getId(), effectiveSkillId, session.getCurrentPhase());

        return buildSessionResponse(session);
    }

    /**
     * 仅当会话处于 IN_PROGRESS 状态时结束，用于 WebSocket 异常断开的兜底。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        endSession(session);
    }

    /**
     * 结束面试会话并更新状态
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);

        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        endSession(session);
        // 入队失败时由调用方直接标记评估失败，避免前端一直停留在等待状态
        if (voiceEvaluateStreamProducer.sendEvaluateTask(sessionId) == null) {
            updateEvaluateStatus(sessionIdLong, AsyncTaskStatus.FAILED, "评估任务入队失败");
        }
    }

    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING.name());
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);

        log.info("Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
                session.getId(), session.getActualDuration());
    }

    /**
     * 通过 ID 获取会话
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionMapper.selectById(sessionId);
    }

    /**
     * 开始新的面试阶段
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);

            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("Invalid phase string: {}", phaseStr, e);
        }
    }

    /**
     * 获取会话当前阶段
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * 保存对话消息（用户和 AI 文本）到数据库
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
            && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase() != null ? session.getCurrentPhase().name() : null)
                .userRecognizedText(normalizedUserText != null && !answerAttached
                    ? normalizedUserText
                    : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .timestamp(now)
                .createdAt(now)
                .build();

        messageMapper.insert(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        VoiceInterviewMessageEntity message = messageMapper.selectOne(
                new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                        .eq(VoiceInterviewMessageEntity::getSessionId, sessionId)
                        .isNull(VoiceInterviewMessageEntity::getUserRecognizedText)
                        .isNotNull(VoiceInterviewMessageEntity::getAiGeneratedText)
                        .orderByDesc(VoiceInterviewMessageEntity::getSequenceNum)
                        .last("LIMIT 1")
        );

        if (message == null) {
            return false;
        }

        message.setUserRecognizedText(userText);
        messageMapper.updateById(message);
        log.debug("Filled answer for voice message: sessionId={}, sequence={}",
            sessionId, message.getSequenceNum());
        return true;
    }

    /**
     * 获取会话的对话历史记录
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageMapper.selectList(
                new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                        .eq(VoiceInterviewMessageEntity::getSessionId, sessionIdLong)
                        .orderByAsc(VoiceInterviewMessageEntity::getSequenceNum)
        );
    }

    /**
     * 获取对话历史 DTO（用于前端）
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
            .map(msg -> VoiceInterviewMessageDTO.builder()
                .id(msg.getId())
                .sessionId(msg.getSessionId())
                .messageType(msg.getMessageType())
                .phase(msg.getPhase())
                .userRecognizedText(msg.getUserRecognizedText())
                .aiGeneratedText(msg.getAiGeneratedText())
                .timestamp(msg.getTimestamp())
                .sequenceNum(msg.getSequenceNum())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 暂停面试会话
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    /**
     * 恢复面试会话
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionIdLong);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        log.info("Session {} resumed with {} messages in conversation history",
            sessionId, messageMapper.selectCount(
                new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                    .eq(VoiceInterviewMessageEntity::getSessionId, sessionIdLong)));

        return buildSessionResponse(session);
    }

    /**
     * 获取用户所有会话
     */
    public List<SessionMetaDTO> getAllSessions(String userId, String status) {
        userId = userId != null ? userId : DEFAULT_USER_ID;

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<VoiceInterviewSessionEntity>()
                            .eq(VoiceInterviewSessionEntity::getUserId, userId)
                            .eq(VoiceInterviewSessionEntity::getStatus, statusEnum)
                            .orderByDesc(VoiceInterviewSessionEntity::getUpdatedAt)
            );
        } else {
            sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<VoiceInterviewSessionEntity>()
                            .eq(VoiceInterviewSessionEntity::getUserId, userId)
                            .orderByDesc(VoiceInterviewSessionEntity::getUpdatedAt)
            );
        }

        return sessions.stream()
            .map(session -> SessionMetaDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .status(session.getStatus().name())
                .currentPhase(session.getCurrentPhase() != null ? session.getCurrentPhase().name() : null)
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .actualDuration(session.getActualDuration())
                .messageCount(messageMapper.selectCount(
                    new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                        .eq(VoiceInterviewMessageEntity::getSessionId, session.getId())))
                .evaluateStatus(session.getEvaluateStatus())
                .evaluateError(session.getEvaluateError())
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 获取会话 DTO
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                                LocalDateTime phaseStartTime,
                                                int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("Phase {} reached max duration {} minutes, forcing transition",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        if (questionCount >= config.getMaxQuestions()) {
            log.info("Phase {} reached max questions {}, suggesting transition",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * 更新评估状态
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionId);
            if (session != null) {
                session.setEvaluateStatus(status.name());
                session.setEvaluateError(error);
                session.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(session);
                log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status);
            }
        } catch (Exception e) {
            log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 触发异步评估（由 Controller 调用）
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        VoiceInterviewSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationMapper.delete(
                new LambdaQueryWrapper<VoiceInterviewEvaluationEntity>()
                        .eq(VoiceInterviewEvaluationEntity::getSessionId, sessionId));
        messageMapper.delete(
                new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                        .eq(VoiceInterviewMessageEntity::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    /**
     * 清理超时的 IN_PROGRESS 会话和卡住的 PROCESSING 评估。
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<VoiceInterviewSessionEntity>()
                        .eq(VoiceInterviewSessionEntity::getStatus, VoiceInterviewSessionStatus.IN_PROGRESS)
                        .lt(VoiceInterviewSessionEntity::getStartTime, staleThreshold)
        );

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("Cleaning up stale IN_PROGRESS session {}, started at {}",
                session.getId(), session.getStartTime());
            endSession(session);
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now().minusMinutes(30);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionMapper.selectList(
                new LambdaQueryWrapper<VoiceInterviewSessionEntity>()
                        .eq(VoiceInterviewSessionEntity::getEvaluateStatus, AsyncTaskStatus.PROCESSING.name())
                        .lt(VoiceInterviewSessionEntity::getUpdatedAt, evalStaleThreshold)
        );

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("Resetting stuck PROCESSING evaluation for session {}", session.getId());
            session.setEvaluateStatus(AsyncTaskStatus.FAILED.name());
            session.setEvaluateError("评估超时，请重新触发");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            cleaned++;
        }

        return cleaned;
    }

    // ==================== Private Helper Methods ====================

    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase() != null ? session.getCurrentPhase().name() : null)
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(buildWebSocketUrl(session.getId()))
                .build();
    }

    /**
     * 基于当前请求动态构造 WebSocket 地址（兼容局域网访问与 Nginx 反向代理），
     * 修复原实现写死 ws://localhost:8080 导致非本机访问无法连接的问题。
     */
    private String buildWebSocketUrl(Long sessionId) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                jakarta.servlet.http.HttpServletRequest request = attrs.getRequest();
                // 反向代理场景优先使用转发头
                String scheme = request.getHeader("X-Forwarded-Proto");
                if (scheme == null || scheme.isBlank()) {
                    scheme = request.getScheme();
                }
                String host = request.getHeader("X-Forwarded-Host");
                if (host == null || host.isBlank()) {
                    host = request.getServerName();
                    int port = request.getServerPort();
                    boolean secure = "https".equalsIgnoreCase(scheme);
                    if (!((secure && port == 443) || (!secure && port == 80))) {
                        host = host + ":" + port;
                    }
                }
                String wsScheme = "https".equalsIgnoreCase(scheme) ? "wss" : "ws";
                return String.format("%s://%s/ws/voice-interview/%d", wsScheme, host, sessionId);
            }
        } catch (Exception e) {
            log.warn("构建 WebSocket URL 失败，使用默认地址: {}", e.getMessage());
        }
        return String.format("ws://localhost:8080/ws/voice-interview/%d", sessionId);
    }

    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    private int getNextSequenceNum(Long sessionId) {
        Long count = messageMapper.selectCount(
                new LambdaQueryWrapper<VoiceInterviewMessageEntity>()
                        .eq(VoiceInterviewMessageEntity::getSessionId, sessionId));
        return count.intValue() + 1;
    }

    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId, e);
            return null;
        }
    }
}

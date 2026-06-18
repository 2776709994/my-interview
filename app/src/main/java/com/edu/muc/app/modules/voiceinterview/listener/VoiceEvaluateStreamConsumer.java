package com.edu.muc.app.modules.voiceinterview.listener;

import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.edu.muc.app.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import com.edu.muc.app.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 语音面试评估 Stream 消费者（Redis Stream 模板方法实现）
 * <p>
 * 监听 Redis Stream: voice-interview:evaluation。
 * 状态机：PENDING →（领取）→ PROCESSING → COMPLETED/FAILED（超过 3 次重试）。
 * </p>
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<Long> {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final ExecutorService evaluationExecutor;

    public VoiceEvaluateStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                       StreamPendingRecoverer pendingRecoverer,
                                       VoiceInterviewService voiceInterviewService,
                                       VoiceInterviewEvaluationService evaluationService,
                                       @Qualifier("voiceEvaluationExecutor") ExecutorService evaluationExecutor) {
        super(redisTemplate, pendingRecoverer);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.evaluationExecutor = evaluationExecutor;
    }

    @Override
    protected ExecutorService executor() {
        return evaluationExecutor;
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Long sessionId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, String.valueOf(sessionId));
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-listener";
    }

    @Override
    protected Long parsePayload(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        return raw == null ? null : Long.parseLong(raw.toString().trim());
    }

    @Override
    protected String payloadIdentifier(Long sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    @Override
    protected boolean shouldSkip(Long sessionId) {
        // 已完成评估的会话跳过（幂等：重复投递不重复评估）
        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        return session != null && AsyncTaskStatus.COMPLETED.name().equals(session.getEvaluateStatus());
    }

    @Override
    protected boolean tryMarkProcessing(Long sessionId) {
        voiceInterviewService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PROCESSING, null);
        return true;
    }

    @Override
    protected void processBusiness(Long sessionId) throws Exception {
        evaluationService.generateEvaluation(sessionId);
    }

    @Override
    protected void markCompleted(Long sessionId) {
        voiceInterviewService.updateEvaluateStatus(sessionId, AsyncTaskStatus.COMPLETED, null);
        log.info("✅ 语音面试评估完成: sessionId={}", sessionId);
    }

    @Override
    protected void markFailed(Long sessionId, String error) {
        log.error("❌ 语音面试评估最终失败: sessionId={}, 原因: {}", sessionId, error);
        voiceInterviewService.updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, error);
    }
}

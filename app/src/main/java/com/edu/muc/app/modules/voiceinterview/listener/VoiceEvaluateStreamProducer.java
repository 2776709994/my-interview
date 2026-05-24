package com.edu.muc.app.modules.voiceinterview.listener;

import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者（Redis Stream）
 * <p>
 * 注意：本组件不依赖任何 Service（避免与 VoiceInterviewService 形成构造器循环依赖），
 * 入队失败时返回 null，由调用方负责更新评估状态。
 * </p>
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer {

    private static final String STREAM_KEY = AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;

    private final RedisTemplate<String, Object> redisTemplate;

    public VoiceEvaluateStreamProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送语音面试评估任务
     *
     * @param sessionId 语音面试会话ID
     * @return 消息ID；入队失败时返回 null（由调用方更新评估状态）
     */
    public RecordId sendEvaluateTask(String sessionId) {
        try {
            RecordId id = redisTemplate.opsForStream().add(
                    StreamRecords.newRecord()
                            .in(STREAM_KEY)
                            .ofMap(Map.of(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId,
                                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"))
            );
            // 裁剪旧消息，防止 Stream 无限增长
            try {
                redisTemplate.opsForStream().trim(STREAM_KEY, AsyncTaskStreamConstants.STREAM_MAX_LEN);
            } catch (Exception trimEx) {
                log.warn("裁剪语音评估 Stream 失败: {}", trimEx.getMessage());
            }
            log.info("语音面试评估任务已入队: sessionId={}, messageId={}", sessionId, id);
            return id;
        } catch (Exception e) {
            log.error("语音面试评估任务入队失败: sessionId={}", sessionId, e);
            return null;
        }
    }
}

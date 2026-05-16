package com.edu.muc.app.modules.voiceinterview.listener;

import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者（Redis Stream）
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer {

    private static final String STREAM_KEY = "voice-interview:evaluation";

    private final RedisTemplate<String, Object> redisTemplate;
    private final VoiceInterviewService voiceInterviewService;

    public VoiceEvaluateStreamProducer(RedisTemplate<String, Object> redisTemplate,
                                       VoiceInterviewService voiceInterviewService) {
        this.redisTemplate = redisTemplate;
        this.voiceInterviewService = voiceInterviewService;
    }

    public RecordId sendEvaluateTask(String sessionId) {
        try {
            RecordId id = redisTemplate.opsForStream().add(
                    StreamRecords.newRecord()
                            .in(STREAM_KEY)
                            .ofMap(Map.of("voiceSessionId", sessionId, "retryCount", "0"))
            );
            log.info("语音面试评估任务已入队: sessionId={}, messageId={}", sessionId, id);
            return id;
        } catch (Exception e) {
            log.error("语音面试评估任务入队失败: sessionId={}", sessionId, e);
            voiceInterviewService.updateEvaluateStatus(
                    Long.parseLong(sessionId), AsyncTaskStatus.FAILED, "评估任务入队失败: " + e.getMessage());
            return null;
        }
    }
}

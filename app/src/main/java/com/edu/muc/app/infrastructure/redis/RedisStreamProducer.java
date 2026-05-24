package com.edu.muc.app.infrastructure.redis;

import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class RedisStreamProducer {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisStreamProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送简历分析任务到 Redis Stream
     * @param resumeId 简历ID
     * @return 消息ID
     */
    public RecordId sendResumeAnalysisTask(String resumeId) {
        return addAndTrim(AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                Map.of(AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId));
    }

    /**
     * 发送面试评估任务到 Redis Stream
     * @param sessionId 面试会话ID
     * @return 消息ID
     */
    public RecordId sendInterviewEvaluationTask(String sessionId) {
        return addAndTrim(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId));
    }

    /**
     * 写入消息并按 STREAM_MAX_LEN 裁剪旧消息，防止 Stream 无限增长。
     * 裁剪失败只记录日志，不影响消息写入。
     */
    private RecordId addAndTrim(String streamKey, Map<String, String> fields) {
        RecordId id = redisTemplate.opsForStream().add(
                StreamRecords.newRecord()
                        .in(streamKey)
                        .ofMap(fields)
        );
        try {
            redisTemplate.opsForStream().trim(streamKey, AsyncTaskStreamConstants.STREAM_MAX_LEN);
        } catch (Exception e) {
            log.warn("裁剪 Redis Stream 失败（key={}）: {}", streamKey, e.getMessage());
        }
        return id;
    }
}

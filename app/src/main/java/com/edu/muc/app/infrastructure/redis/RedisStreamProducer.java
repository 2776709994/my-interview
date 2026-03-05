package com.edu.muc.app.infrastructure.redis;

import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

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
        return redisTemplate.opsForStream().add(
                StreamRecords.newRecord()
                        .in("resume:analysis")
                        .ofMap(Map.of("resumeId", resumeId))
        );
    }

    /**
     * 发送面试评估任务到 Redis Stream
     * @param sessionId 面试会话ID
     * @return 消息ID
     */
    public RecordId sendInterviewEvaluationTask(String sessionId) {
        return redisTemplate.opsForStream().add(
                StreamRecords.newRecord()
                        .in("interview:evaluation")
                        .ofMap(Map.of("sessionId", sessionId))
        );
    }
}
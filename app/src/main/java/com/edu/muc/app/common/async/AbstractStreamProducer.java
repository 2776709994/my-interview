package com.edu.muc.app.common.async;

import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 生产者模板基类。
 * <p>
 * 模板方法统一"构建消息 → XADD 入队（接口入队即返回，不等待消费结果）→ XTRIM 裁剪 →
 * 失败兜底"骨架，子类只需提供 Stream Key、消息字段与入队失败的处理逻辑。
 * 每条消息默认携带 {@code retryCount=0}，与 {@link AbstractStreamConsumer} 配合实现失败自动重试。
 * </p>
 *
 * @param <T> 业务载荷类型
 */
@Slf4j
public abstract class AbstractStreamProducer<T> {

    protected final RedisTemplate<String, Object> redisTemplate;

    protected AbstractStreamProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送任务消息到目标 Stream
     *
     * @return 是否入队成功
     */
    protected boolean sendTask(T payload) {
        try {
            Map<String, String> message = new HashMap<>(buildMessage(payload));
            message.putIfAbsent(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
            RecordId messageId = redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().in(streamKey()).ofMap(message));
            trimStream();
            log.info("{}任务已入队: payload={}, messageId={}",
                    taskDisplayName(), payloadIdentifier(payload), messageId);
            return true;
        } catch (Exception e) {
            log.error("发送{}任务失败: payload={}, error={}",
                    taskDisplayName(), payloadIdentifier(payload), e.getMessage(), e);
            onSendFailed(payload, truncateError("任务入队失败: " + e.getMessage()));
            return false;
        }
    }

    /**
     * 按常量上限裁剪 Stream，防止无限增长；裁剪失败只记录日志，不影响消息写入
     */
    private void trimStream() {
        try {
            redisTemplate.opsForStream().trim(streamKey(), AsyncTaskStreamConstants.STREAM_MAX_LEN);
        } catch (Exception e) {
            log.warn("裁剪 Redis Stream 失败（key={}）: {}", streamKey(), e.getMessage());
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /** 任务显示名（日志用） */
    protected abstract String taskDisplayName();

    /** 目标 Stream Key */
    protected abstract String streamKey();

    /**
     * 构建消息字段。retryCount 由模板统一填充，子类无需关心
     */
    protected abstract Map<String, String> buildMessage(T payload);

    /** 载荷标识（日志用） */
    protected abstract String payloadIdentifier(T payload);

    /**
     * 入队失败兜底（如将任务状态标记为 FAILED，避免前端无限轮询），由子类实现
     */
    protected abstract void onSendFailed(T payload, String error);
}

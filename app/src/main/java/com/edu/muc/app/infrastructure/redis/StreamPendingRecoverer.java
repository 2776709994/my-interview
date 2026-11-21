package com.edu.muc.app.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Stream PEL（Pending Entries List）恢复工具。
 * <p>
 * 消费者异常退出/处理失败后，消息会滞留在 PEL 中且不会被重新投递（XREADGROUP
 * 的 lastConsumed 只返回新消息）。本工具分页认领 idle 超过 {@link #MIN_IDLE} 的消息，
 * 由消费者按业务重试次数处理，直到业务状态写入成功。
 * </p>
 */
@Slf4j
@Component
public class StreamPendingRecoverer {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 消息 idle 超过该时长视为"处理中挂起"，可被认领（需大于单任务最长处理时间） */
    private static final Duration MIN_IDLE = Duration.ofMinutes(5);


    /** 每次最多扫描的 PEL 消息数 */
    private static final int SCAN_LIMIT = 100;
    private final Map<String, String> scanOffsets = new ConcurrentHashMap<>();

    public StreamPendingRecoverer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 扫描并恢复指定 Stream 消费者组中的滞留消息。
     *
     * @param streamKey    Stream Key
     * @param group        消费者组
     * @param consumerName 当前消费者名称（认领后消息归属该消费者）
     * @return 认领到的消息列表（由调用方按统一语义重新处理并 ACK）
     */
    public List<MapRecord<String, Object, Object>> recover(String streamKey, String group, String consumerName) {
        try {
            String scanKey = streamKey + ":" + group;
            String offset = scanOffsets.get(scanKey);
            Range<String> range = offset == null ? Range.unbounded()
                    : Range.from(Range.Bound.exclusive(offset)).to(Range.Bound.unbounded());
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, group, range, SCAN_LIMIT);
            if (pending == null || pending.isEmpty()) {
                scanOffsets.remove(scanKey);
                return List.of();
            }

            List<RecordId> toClaim = new ArrayList<>();
            RecordId lastId = null;
            for (PendingMessage msg : pending) {
                lastId = msg.getId();
                long idleSeconds = msg.getElapsedTimeSinceLastDelivery() != null
                        ? msg.getElapsedTimeSinceLastDelivery().getSeconds() : 0;
                if (idleSeconds >= MIN_IDLE.toSeconds()) {
                    toClaim.add(msg.getId());
                }
            }
            if (pending.size() == SCAN_LIMIT && lastId != null) {
                scanOffsets.put(scanKey, lastId.getValue());
            } else {
                scanOffsets.remove(scanKey);
            }

            if (toClaim.isEmpty()) {
                return List.of();
            }

            log.info("[{}] 认领 {} 条超时未确认消息，重新处理", streamKey, toClaim.size());
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(streamKey, group, consumerName, MIN_IDLE, toClaim.toArray(new RecordId[0]));
            // 清理旧版本裁剪后遗留的空消息。
            List<MapRecord<String, Object, Object>> valid = new ArrayList<>(claimed.size());
            for (MapRecord<String, Object, Object> record : claimed) {
                if (record.getValue() == null || record.getValue().isEmpty()) {
                    log.warn("[{}] 认领到空消息体（可能已被裁剪），直接 ACK: {}", streamKey, record.getId());
                    redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
                } else {
                    valid.add(record);
                }
            }
            return valid;
        } catch (Exception e) {
            log.warn("[{}] PEL 恢复扫描失败: {}", streamKey, e.getMessage());
            return List.of();
        }
    }
}

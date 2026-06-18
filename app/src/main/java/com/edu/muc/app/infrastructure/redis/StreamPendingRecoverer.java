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

/**
 * Redis Stream PEL（Pending Entries List）恢复工具。
 * <p>
 * 消费者异常退出/处理失败后，消息会滞留在 PEL 中且不会被重新投递（XREADGROUP
 * 的 lastConsumed 只返回新消息）。本工具将 idle 超过 {@link #MIN_IDLE} 且
 * 投递次数未超限的消息 claim 到当前消费者并返回，由调用方（消费者模板）按统一的
 * 重试语义重新处理；投递次数超过 {@link #MAX_DELIVERY} 的消息直接 ACK 放弃
 * （等效死信），避免无限重试。
 * </p>
 */
@Slf4j
@Component
public class StreamPendingRecoverer {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 消息 idle 超过该时长视为"处理中挂起"，可被认领（需大于单任务最长处理时间） */
    private static final Duration MIN_IDLE = Duration.ofMinutes(5);

    /** 最大投递次数，超过则放弃（ACK） */
    private static final int MAX_DELIVERY = 3;

    /** 每次最多扫描的 PEL 消息数 */
    private static final int SCAN_LIMIT = 100;

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
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, group, Range.unbounded(), SCAN_LIMIT);
            if (pending == null || pending.isEmpty()) {
                return List.of();
            }

            List<RecordId> toClaim = new ArrayList<>();
            List<RecordId> toDiscard = new ArrayList<>();
            for (PendingMessage msg : pending) {
                long idleSeconds = msg.getElapsedTimeSinceLastDelivery() != null
                        ? msg.getElapsedTimeSinceLastDelivery().getSeconds() : 0;
                long deliveryCount = msg.getTotalDeliveryCount();
                if (idleSeconds < MIN_IDLE.toSeconds()) {
                    continue;
                }
                if (deliveryCount >= MAX_DELIVERY) {
                    toDiscard.add(msg.getId());
                } else {
                    toClaim.add(msg.getId());
                }
            }

            if (!toDiscard.isEmpty()) {
                log.warn("[{}] {} 条消息投递超过 {} 次仍未成功，放弃并 ACK（消息ID: {}）",
                        streamKey, toDiscard.size(), MAX_DELIVERY, toDiscard);
                redisTemplate.opsForStream().acknowledge(streamKey, group, toDiscard.toArray(new RecordId[0]));
            }

            if (toClaim.isEmpty()) {
                return List.of();
            }

            log.info("[{}] 认领 {} 条超时未确认消息，重新处理", streamKey, toClaim.size());
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                    .claim(streamKey, group, consumerName, MIN_IDLE, toClaim.toArray(new RecordId[0]));
            // 消息体可能已被 XTRIM 裁剪（PEL 残留空消息），直接 ACK 清理
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

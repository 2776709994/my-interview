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
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Redis Stream PEL（Pending Entries List）恢复工具。
 * <p>
 * 消费者异常退出/处理失败后，消息会滞留在 PEL 中且不会被重新投递（XREADGROUP
 * 的 lastConsumed 只返回新消息）。本工具周期性将 idle 超过 {@link #MIN_IDLE} 且
 * 投递次数未超限的消息 claim 到当前消费者重新处理；投递次数超过 {@link #MAX_DELIVERY}
 * 的消息直接 ACK 放弃（等效死信），避免无限重试。
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
     * @param streamKey     Stream Key
     * @param group         消费者组
     * @param consumerName  当前消费者名称（认领后消息归属该消费者）
     * @param executor      处理消息用的线程池
     * @param handler       消息处理回调（入参为消息字段 Map，处理成功返回即可）
     */
    public void recover(String streamKey, String group, String consumerName,
                        ExecutorService executor, Consumer<Map<Object, Object>> handler) {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, group, Range.unbounded(), SCAN_LIMIT);
            if (pending == null || pending.isEmpty()) {
                return;
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

            if (!toClaim.isEmpty()) {
                log.info("[{}] 认领 {} 条超时未确认消息，重新处理", streamKey, toClaim.size());
                List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                        .claim(streamKey, group, consumerName, MIN_IDLE, toClaim.toArray(new RecordId[0]));
                for (MapRecord<String, Object, Object> record : claimed) {
                    Map<Object, Object> fields = record.getValue();
                    executor.submit(() -> {
                        try {
                            handler.accept(fields);
                            redisTemplate.opsForStream().acknowledge(streamKey, group, record.getId());
                        } catch (Exception e) {
                            log.error("[{}] 认领消息处理失败，将留在 PEL 中等待下次认领，消息ID: {}",
                                    streamKey, record.getId(), e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("[{}] PEL 恢复扫描失败: {}", streamKey, e.getMessage());
        }
    }
}

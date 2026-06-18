package com.edu.muc.app.common.async;

import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类。
 * <p>
 * 模板方法统一消费者生命周期：{@code @PostConstruct 启动守护消费线程 → XREADGROUP
 * 拉取（消费者组 + ACK 机制）→ 幂等领取任务 → 业务处理 → 状态机流转 → ACK}，并内置：
 * </p>
 * <ul>
 *   <li><b>自动重试</b>：业务失败且 retryCount &lt; {@value AsyncTaskStreamConstants#MAX_RETRY_COUNT}
 *       时重新入队（携带 retryCount+1）并 ACK 原消息；超过次数则 {@link #markFailed} 终态化</li>
 *   <li><b>PEL 恢复</b>：周期性认领消费者宕机遗留的 pending 消息，按同一处理语义重投</li>
 *   <li><b>毒消息防护</b>：解析失败的消息直接 ACK 丢弃，避免阻塞消费</li>
 *   <li><b>自愈</b>：Redis 断连自动退避重连；消费组未就绪（Stream 尚未创建）时逐轮补建</li>
 * </ul>
 * <p>
 * 状态机约定：PENDING →（领取）→ PROCESSING →（成功）→ COMPLETED /（超过重试）→ FAILED。
 * {@link #tryMarkProcessing} 需实现状态守卫（仅 PENDING/PROCESSING 可领取）保证幂等，
 * 使"至少一次投递"在重复消息下不产生重复处理。
 * </p>
 *
 * @param <T> 业务载荷类型
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    protected final RedisTemplate<String, Object> redisTemplate;
    private final StreamPendingRecoverer pendingRecoverer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;
    private String consumerName;
    private volatile boolean groupReady = false;

    protected AbstractStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                     StreamPendingRecoverer pendingRecoverer) {
        this.redisTemplate = redisTemplate;
        this.pendingRecoverer = pendingRecoverer;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        running.set(true);
        consumerThread = new Thread(this::consumeLoop, threadName());
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("{} consumer started: stream={}, group={}, consumerName={}",
                taskDisplayName(), streamKey(), groupName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (consumerThread != null && consumerThread.isAlive()) {
            consumerThread.interrupt();
        }
        ExecutorService executor = executor();
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("{} consumer executor未在60秒内结束，强制关闭", taskDisplayName());
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /** 消费主循环：拉取 → 派发；周期性恢复 PEL 遗留消息 */
    private void consumeLoop() {
        int loopCount = 0;
        while (running.get()) {
            try {
                if (!groupReady) {
                    groupReady = ensureGroup();
                }
                if (groupReady) {
                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                            .read(Consumer.from(groupName(), consumerName),
                                    StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                                    StreamOffset.create(streamKey(), ReadOffset.lastConsumed()));
                    if (messages != null) {
                        for (MapRecord<String, Object, Object> record : messages) {
                            dispatch(record);
                        }
                    }
                } else {
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RedisConnectionFailureException e) {
                log.warn("{} consumer Redis连接断开，5秒后重试: {}", taskDisplayName(), e.getMessage());
                sleepQuietly(5000);
            } catch (Exception e) {
                log.error("{} consumer 拉取消息异常", taskDisplayName(), e);
                sleepQuietly(5000);
            }
            // 周期性认领宕机遗留的 pending 消息（约每 30 秒一次）
            if (++loopCount % 15 == 0 && groupReady) {
                recoverPending();
            }
        }
        log.info("{} consumer loop exited: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 创建消费者组（幂等：BUSYGROUP 视为已就绪）。
     * Redis 要求 XGROUP 的 key 必须已存在，Stream 尚未创建时返回 false，
     * 待生产者首次 XADD 建流后下轮循环自动补建。
     */
    private boolean ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamKey(), groupName());
            return true;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            log.debug("{} consumer group 未就绪（等待 Stream 创建）: {}", taskDisplayName(), e.getMessage());
            return false;
        }
    }

    /** 解析载荷并提交到业务线程池；解析失败 ACK 丢弃（毒消息） */
    private void dispatch(MapRecord<String, Object, Object> record) {
        T payload;
        try {
            payload = parsePayload(record);
        } catch (Exception e) {
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}, error={}",
                    taskDisplayName(), record.getId(),
                    record.getValue() == null ? null : record.getValue().keySet(), e.getMessage());
            ack(record.getId());
            return;
        }
        if (payload == null) {
            ack(record.getId());
            return;
        }
        int retryCount = parseRetryCount(record.getValue());
        executor().submit(() -> processRecord(record.getId(), payload, retryCount));
    }

    /**
     * 单条消息的完整处理语义：幂等领取 → 业务处理 → 完成终态 → ACK；
     * 失败时自动重试（重新入队）或终态化（FAILED），两种情况原消息均 ACK 出队。
     */
    private void processRecord(RecordId messageId, T payload, int retryCount) {
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
                taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);
        try {
            if (shouldSkip(payload)) {
                ack(messageId);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (!tryMarkProcessing(payload)) {
                ack(messageId);
                log.info("{} task not claimed（状态守卫不通过，可能已被处理）: messageId={}",
                        taskDisplayName(), messageId);
                return;
            }
            processBusiness(payload);
            markCompleted(payload);
            ack(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: payload={}, messageId={}, retryCount={}",
                    taskDisplayName(), payloadIdentifier(payload), messageId, retryCount, e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                // 重试通过"新消息 + retryCount+1"承载；原消息 ACK 出队
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                        taskDisplayName() + " failed after " + retryCount + " attempts: " + e.getMessage()));
            }
            ack(messageId);
        }
    }

    /**
     * 默认重试实现：以 {@code retryCount=nextRetryCount} 重新入队同一载荷。
     * 重新入队失败时不 ACK（抛出异常中断 ACK 流程），消息留在 PEL 由恢复器兜底重投。
     */
    protected void retryMessage(T payload, int nextRetryCount) {
        Map<String, String> message = new HashMap<>(buildMessage(payload));
        message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(nextRetryCount));
        try {
            RecordId id = redisTemplate.opsForStream().add(
                    StreamRecords.newRecord().in(streamKey()).ofMap(message));
            log.warn("{} task re-enqueued for retry #{}: payload={}, messageId={}",
                    taskDisplayName(), nextRetryCount, payloadIdentifier(payload), id);
        } catch (Exception e) {
            log.error("{} task 重新入队失败，消息留在 PEL 等待恢复: payload={}",
                    taskDisplayName(), payloadIdentifier(payload), e);
            throw new IllegalStateException("重新入队失败: " + e.getMessage(), e);
        }
    }

    /** 认领宕机遗留的 pending 消息并按同一语义处理 */
    private void recoverPending() {
        try {
            List<MapRecord<String, Object, Object>> claimed =
                    pendingRecoverer.recover(streamKey(), groupName(), consumerName);
            for (MapRecord<String, Object, Object> record : claimed) {
                dispatch(record);
            }
        } catch (Exception e) {
            log.warn("{} consumer PEL 恢复扫描失败: {}", taskDisplayName(), e.getMessage());
        }
    }

    private int parseRetryCount(Map<Object, Object> fields) {
        if (fields == null) {
            return 0;
        }
        Object raw = fields.get(AsyncTaskStreamConstants.FIELD_RETRY_COUNT);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void ack(RecordId messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /** 承载业务处理的线程池（IO 密集型任务建议使用 Java 21 虚拟线程） */
    protected abstract ExecutorService executor();

    /** 任务显示名（日志用） */
    protected abstract String taskDisplayName();

    /** 消费的 Stream Key */
    protected abstract String streamKey();

    /** 构建消息字段（重试重新入队时复用；retryCount 由模板统一填充） */
    protected abstract Map<String, String> buildMessage(T payload);

    /** 消费者组名称 */
    protected abstract String groupName();

    /** 消费者名称前缀（自动追加随机后缀区分实例） */
    protected abstract String consumerPrefix();

    /** 消费守护线程名 */
    protected abstract String threadName();

    /**
     * 从消息字段解析业务载荷；返回 null 表示忽略该消息；
     * 抛异常时该消息被 ACK 丢弃（毒消息防护）
     */
    protected abstract T parsePayload(MapRecord<String, Object, Object> record);

    /** 载荷标识（日志用） */
    protected abstract String payloadIdentifier(T payload);

    /** 幂等跳过（如任务已完成），默认不跳过 */
    protected boolean shouldSkip(T payload) {
        return false;
    }

    /**
     * 领取任务：将业务状态 PENDING→PROCESSING（需带状态守卫保证幂等，
     * 且允许 PROCESSING→PROCESSING 以支持宕机重投）。
     *
     * @return false 表示未领取（如任务已完成/已取消），消息将被 ACK 丢弃
     */
    protected abstract boolean tryMarkProcessing(T payload);

    /**
     * 业务处理主体（耗时操作，运行在 {@link #executor()} 提供的线程池中）。
     * 抛出异常将触发自动重试（最多 {@value AsyncTaskStreamConstants#MAX_RETRY_COUNT} 次）
     */
    protected abstract void processBusiness(T payload) throws Exception;

    /** 任务完成终态（COMPLETED）；若业务处理内部已落终态可实现为空操作 */
    protected abstract void markCompleted(T payload);

    /** 任务失败终态（FAILED），超过最大重试次数后调用 */
    protected abstract void markFailed(T payload, String error);
}

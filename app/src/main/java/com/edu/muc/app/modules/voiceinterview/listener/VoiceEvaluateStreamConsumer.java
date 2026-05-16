package com.edu.muc.app.modules.voiceinterview.listener;

import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import com.edu.muc.app.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 语音面试评估 Stream 消费者
 * 监听 Redis Stream: voice-interview:evaluation
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final ExecutorService executor;

    private static final String STREAM_KEY = "voice-interview:evaluation";
    private static final String GROUP = "voice-interview-evaluation-group";

    private Thread listenerThread;

    public VoiceEvaluateStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                       VoiceInterviewService voiceInterviewService,
                                       VoiceInterviewEvaluationService evaluationService,
                                       @org.springframework.beans.factory.annotation.Qualifier("interviewEvaluationExecutor")
                                       ExecutorService executor) {
        this.redisTemplate = redisTemplate;
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.executor = executor;
    }

    @PostConstruct
    public void start() {
        log.info("✅ Redis Stream 语音面试评估消费者已启动，开始监听 {}", STREAM_KEY);

        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                log.warn("创建消费者组异常（可能已存在）: {}", e.getMessage());
            }
        }

        String consumerName = "voice-eval-consumer-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        listenerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                            .read(
                                    Consumer.from(GROUP, consumerName),
                                    StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                            );

                    if (messages != null && !messages.isEmpty()) {
                        for (MapRecord<String, Object, Object> record : messages) {
                            String sessionId = (String) record.getValue().get("voiceSessionId");
                            RecordId recordId = record.getId();

                            executor.submit(() -> {
                                try {
                                    processVoiceEvaluation(sessionId);
                                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);
                                } catch (Exception e) {
                                    log.error("❌ 语音面试评估任务异常，消息将保留在 PEL 中等待重试，sessionId: {}", sessionId, e);
                                }
                            });
                        }
                    }
                } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                    log.warn("⚠️ Redis 连接断开，5秒后重试... {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    log.error("❌ 监听 Redis Stream 异常", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("🛑 语音面试评估监听线程已退出");
        }, "voice-evaluate-listener");

        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("✅ 语音面试评估监听线程已启动: {}, 守护: {}", listenerThread.getName(), listenerThread.isDaemon());
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 开始关闭语音面试评估消费者...");
        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理语音面试评估
     */
    private void processVoiceEvaluation(String sessionId) {
        log.info("🚀 开始处理语音面试评估, sessionId: {}", sessionId);
        Long sessionIdLong = Long.parseLong(sessionId);

        try {
            voiceInterviewService.updateEvaluateStatus(sessionIdLong, AsyncTaskStatus.PROCESSING, null);
            evaluationService.generateEvaluation(sessionIdLong);
            voiceInterviewService.updateEvaluateStatus(sessionIdLong, AsyncTaskStatus.COMPLETED, null);
            log.info("✅ 语音面试评估完成: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("❌ 语音面试评估失败, sessionId: {}", sessionId, e);
            voiceInterviewService.updateEvaluateStatus(sessionIdLong, AsyncTaskStatus.FAILED, e.getMessage());
        }
    }
}

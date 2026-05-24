package com.edu.muc.app.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池配置类
 * 使用 Java 21 虚拟线程（Virtual Threads）替代传统线程池
 * 适用于 IO 密集型任务（AI API 调用、数据库查询、Redis 操作等）
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    /**
     * 简历分析专用虚拟线程 Executor
     * 用于处理耗时的 AI 简历分析任务
     */
    @Bean("resumeAnalysisExecutor")
    public ExecutorService resumeAnalysisExecutor() {
        log.info("✅ 简历分析虚拟线程 Executor 已创建（无并发上限）");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * RAG 查询虚拟线程 Executor
     * 用于知识库检索、流式问答等 IO 密集型任务
     */
    @Bean("ragQueryExecutor")
    public ExecutorService ragQueryExecutor() {
        log.info("✅ RAG 查询虚拟线程 Executor 已创建（无并发上限）");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 面试评估专用虚拟线程 Executor
     * 用于处理面试会话的 AI 评估任务
     */
    @Bean("interviewEvaluationExecutor")
    public ExecutorService interviewEvaluationExecutor() {
        log.info("✅ 面试评估虚拟线程 Executor 已创建（无并发上限）");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 语音面试评估专用虚拟线程 Executor
     * 与文字面试评估隔离，避免相互抢占
     */
    @Bean("voiceEvaluationExecutor")
    public ExecutorService voiceEvaluationExecutor() {
        log.info("✅ 语音面试评估虚拟线程 Executor 已创建（无并发上限）");
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

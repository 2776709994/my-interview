package com.edu.muc.app.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置类
 * 统一管理项目中的所有线程池
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    /**
     * 简历分析专用线程池
     * 用于处理耗时的 AI 分析任务
     */
    @Bean("resumeAnalysisExecutor")
    public ExecutorService resumeAnalysisExecutor() {
        int corePoolSize = 3;           // 核心线程数
        int maximumPoolSize = 5;        // 最大线程数
        long keepAliveTime = 60L;       // 空闲线程存活时间（秒）
        int queueCapacity = 100;        // 队列容量
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new CustomizableThreadFactory("resume-analysis-"),  // 线程名前缀
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行
        );
        
        // 允许核心线程超时回收
        executor.allowCoreThreadTimeOut(true);
        
        log.info("✅ 简历分析线程池已创建: core={}, max={}, queue={}", 
                corePoolSize, maximumPoolSize, queueCapacity);
        
        return executor;
    }

    /**
     * RAG 查询通用线程池
     * 用于知识库检索、流式问答等 IO 密集型任务
     */
    @Bean("ragQueryExecutor")
    public ExecutorService ragQueryExecutor() {
        int corePoolSize = 5;           // 核心线程数
        int maximumPoolSize = 10;       // 最大线程数
        long keepAliveTime = 60L;       // 空闲线程存活时间（秒）
        int queueCapacity = 200;        // 队列容量
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new CustomizableThreadFactory("rag-query-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        executor.allowCoreThreadTimeOut(true);
        
        log.info("✅ RAG 查询线程池已创建: core={}, max={}, queue={}", 
                corePoolSize, maximumPoolSize, queueCapacity);
        
        return executor;
    }

    /**
     * 面试评估专用线程池
     * 用于处理面试会话的 AI 评估任务
     */
    @Bean("interviewEvaluationExecutor")
    public ExecutorService interviewEvaluationExecutor() {
        int corePoolSize = 2;           // 核心线程数
        int maximumPoolSize = 4;        // 最大线程数
        long keepAliveTime = 60L;       // 空闲线程存活时间（秒）
        int queueCapacity = 50;         // 队列容量
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new CustomizableThreadFactory("interview-eval-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        executor.allowCoreThreadTimeOut(true);
        
        log.info("✅ 面试评估线程池已创建: core={}, max={}, queue={}", 
                corePoolSize, maximumPoolSize, queueCapacity);
        
        return executor;
    }
}

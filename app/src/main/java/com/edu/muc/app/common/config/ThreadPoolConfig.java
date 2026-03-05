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
}

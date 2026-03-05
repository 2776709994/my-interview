package com.edu.muc.app.common.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Spring AI 客户端超时配置
 * 配置 RestClient 超时时间以支持长时间的 AI 推理任务
 * 
 * 注意：Spring AI Alibaba 1.0.0.2 使用 Spring 6.1 的 RestClient
 * 需要配置底层的 HTTP 请求工厂超时时间
 */
@Configuration
public class SpringAiTimeoutConfig {

    /**
     * 配置 RestClient 的超时时间
     * Spring AI Alibaba 1.0.0.x 使用 RestClient 进行 HTTP 调用
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofMinutes(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofMinutes(5).toMillis());
        
        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    /**
     * 自定义 RestClient 超时配置
     * 确保 Spring AI 使用的 RestClient 有足够长的超时时间
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return builder -> builder
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofMinutes(5).toMillis());
                    setReadTimeout((int) Duration.ofMinutes(5).toMillis());
                }});
    }
}

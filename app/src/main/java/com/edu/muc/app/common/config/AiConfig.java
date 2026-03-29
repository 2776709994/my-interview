package com.edu.muc.app.common.config;

import org.apache.tika.Tika;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder){
        return builder.build();
    }

    /**
     * Tika 文档解析器
     */
    @Bean
    public Tika tika() {
        Tika tika = new Tika();
        tika.setMaxStringLength(10 * 1024 * 1024); // 10MB 限制
        return tika;
    }


}


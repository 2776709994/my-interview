package com.edu.muc.app.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson Configuration
 * Spring Boot 4.0 默认使用 Jackson 3 (tools.jackson)，
 * 需手动注册 Jackson 2 (com.fasterxml) 的 ObjectMapper bean 以兼容现有代码。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

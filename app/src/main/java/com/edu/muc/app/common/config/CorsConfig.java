package com.edu.muc.app.common.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;

@Configuration
public class CorsConfig {

    // 确保这个过滤器的优先级最高
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 允许的来源（支持本机 + 局域网访问）
        config.addAllowedOrigin("http://localhost:5173");   // 本机开发
        config.addAllowedOrigin("http://127.0.0.1:5173");   // 本机 IP
        config.addAllowedOriginPattern("http://192.168.*:5173");  // 局域网 192.168.x.x
        config.addAllowedOriginPattern("http://10.*.*.*:5173");   // 局域网 10.x.x.x
        config.addAllowedOriginPattern("http://172.1[6-9].*:5173");  // 局域网 172.16-19.x.x
        config.addAllowedOriginPattern("http://172.2[0-9].*:5173");  // 局域网 172.20-29.x.x
        config.addAllowedOriginPattern("http://172.3[0-1].*:5173");  // 局域网 172.30-31.x.x
        
        // 如果以上都不够，可以使用通配符（但会与 credentials 冲突，需要注释掉 setAllowCredentials）
        // config.addAllowedOriginPattern("*");

        // 放行所有请求头
        config.addAllowedHeader("*");

        // 显式列出要放行的 HTTP 方法
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");

        // 允许携带 Cookie 等凭证信息
        config.setAllowCredentials(true);

        // 允许前端读取自定义的响应头
        config.addExposedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对所有路径都应用这个配置
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
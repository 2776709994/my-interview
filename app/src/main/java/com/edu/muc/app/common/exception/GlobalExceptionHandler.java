package com.edu.muc.app.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理静态资源未找到异常（Spring Boot 3.5+ 默认行为）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResourceFoundException(NoResourceFoundException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 404);
        response.put("message", "资源未找到");
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBusinessException(BusinessException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", e.getCode());
        response.put("message", e.getMessage());
        response.put("errorCode", e.getErrorCode());
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }
    
    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgumentException(IllegalArgumentException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 400);
        response.put("message", "参数错误: " + e.getMessage());
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }
    
    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        log.error("未捕获的异常", e);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 500);
        response.put("message", "服务器内部错误");
        response.put("timestamp", LocalDateTime.now().toString());

        // 开发环境返回详细错误信息
        if (isDevelopmentEnvironment()) {
            response.put("detail", e.getMessage());
        }

        return response;
    }
    
    /**
     * 判断是否为开发环境
     */
    private boolean isDevelopmentEnvironment() {
        return "dev".equals(System.getProperty("spring.profiles.active", "dev"));
    }
}

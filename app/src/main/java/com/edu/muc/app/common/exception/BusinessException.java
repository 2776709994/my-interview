package com.edu.muc.app.common.exception;

import lombok.Getter;

/**
 * 业务异常类
 * 支持 ErrorCode 枚举和 String errorCode 两种方式
 */
@Getter
public class BusinessException extends RuntimeException {
    
    private final Integer code;
    private final String errorCode;

    // ========== ErrorCode 枚举构造函数（新） ==========

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.errorCode = errorCode.name();
    }
    
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.errorCode = errorCode.name();
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.errorCode = errorCode.name();
    }

    // ========== Integer code 构造函数（新） ==========

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.errorCode = "ERROR_" + code;
    }

    // ========== String errorCode 构造函数（向后兼容） ==========

    public BusinessException(String message) {
        super(message);
        this.code = 500;
        this.errorCode = "BUSINESS_ERROR";
    }
    
    public BusinessException(String errorCode, String message) {
        super(message);
        this.code = 500;
        this.errorCode = errorCode;
    }
    
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
        this.errorCode = "BUSINESS_ERROR";
    }

    public BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
        this.errorCode = errorCode;
    }
}

package com.edu.muc.app.modules.interview.enums;

/**
 * 面试评估状态枚举
 */
public enum EvaluateStatus {
    PENDING("PENDING", "等待评估"),
    PROCESSING("PROCESSING", "评估中"),
    COMPLETED("COMPLETED", "评估完成"),
    FAILED("FAILED", "评估失败");

    private final String code;
    private final String description;

    EvaluateStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static EvaluateStatus fromCode(String code) {
        for (EvaluateStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的评估状态: " + code);
    }
}

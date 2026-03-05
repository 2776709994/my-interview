package com.edu.muc.app.modules.interview.enums;

/**
 * 面试会话状态枚举
 */
public enum SessionStatus {
    CREATED("CREATED", "已创建"),
    IN_PROGRESS("IN_PROGRESS", "进行中"),
    COMPLETED("COMPLETED", "已完成"),
    EVALUATED("EVALUATED", "已评估");

    private final String code;
    private final String description;

    SessionStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static SessionStatus fromCode(String code) {
        for (SessionStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的会话状态: " + code);
    }
}

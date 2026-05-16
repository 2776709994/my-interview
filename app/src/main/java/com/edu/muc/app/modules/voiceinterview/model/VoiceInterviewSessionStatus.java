package com.edu.muc.app.modules.voiceinterview.model;

/**
 * 语音面试会话状态
 */
public enum VoiceInterviewSessionStatus {
    /**
     * 进行中 - WebSocket 已连接，面试进行中
     */
    IN_PROGRESS,

    /**
     * 已暂停 - 用户暂停或超时，状态已保存到 DB
     */
    PAUSED,

    /**
     * 已完成 - 面试结束
     */
    COMPLETED,

    /**
     * 失败 - 发生错误
     */
    FAILED
}

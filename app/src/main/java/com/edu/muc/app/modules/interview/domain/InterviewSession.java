package com.edu.muc.app.modules.interview.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_sessions")
public class InterviewSession {
    @TableId(type = IdType.ASSIGN_ID)
    private String sessionId;

    private Long resumeId;

    private String resumeText;

    private String jdText;

    /** 关联的知识库 ID 列表（JSON 数组字符串），用于 RAG 打通 */
    private String knowledgeBaseIds;

    private String skillId;

    private String difficulty;

    private Integer totalQuestions;

    private Integer currentQuestionIndex;

    private String status; // CREATED, IN_PROGRESS, COMPLETED, EVALUATED

    private String evaluateStatus; // PENDING, PROCESSING, COMPLETED, FAILED

    private String evaluateError;

    private Integer overallScore;

    private String overallFeedback;

    private String strengthsJson;

    private String improvementsJson;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private LocalDateTime evaluatedAt;
}

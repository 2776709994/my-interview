package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class TextSessionMetaDTO {
    private String sessionId;
    private String skillId;
    private String difficulty;
    private Long resumeId;
    private int totalQuestions;
    private String status;
    private String evaluateStatus;
    private String evaluateError;
    private Integer overallScore;
    private String createdAt;
    private String completedAt;
}

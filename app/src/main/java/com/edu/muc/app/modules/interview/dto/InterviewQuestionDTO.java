package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class InterviewQuestionDTO {
    private int questionIndex;
    private String question;
    private String type;
    private String category;
    /** 知识点摘要（10 字以内），用于历史面试去重 */
    private String topicSummary;
    private String userAnswer;
    private Double score;
    private String feedback;
}

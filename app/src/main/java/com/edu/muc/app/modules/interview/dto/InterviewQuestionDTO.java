package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class InterviewQuestionDTO {
    private int questionIndex;
    private String question;
    private String type;
    private String category;
    private String userAnswer;
    private Double score;
    private String feedback;
}

package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class CurrentQuestionResponse {
    private boolean completed;
    private InterviewQuestionDTO question;
    private String message;
}

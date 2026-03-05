package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class SubmitAnswerRequest {
    private String sessionId;
    private int questionIndex;
    private String answer;
}

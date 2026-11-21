package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class SubmitAnswerRequest {
    private String sessionId;
    private Integer questionIndex;
    private String answer;
}

package com.edu.muc.app.modules.interview.dto;

import lombok.Data;
import java.util.List;

@Data
public class InterviewSessionDTO {
    private String sessionId;
    private String resumeText;
    private int totalQuestions;
    private int currentQuestionIndex;
    private List<InterviewQuestionDTO> questions;
    private String status; // CREATED, IN_PROGRESS, COMPLETED, EVALUATED

    @Data
    public static class InterviewQuestionDTO {
        private int questionIndex;
        private String question;
        private String type;
        private String category;
        private String userAnswer;
        private Double score;
        private String feedback;
    }
}

package com.edu.muc.app.modules.interview.dto;

import lombok.Data;

@Data
public class SubmitAnswerResponse {
    private boolean hasNextQuestion;
    private InterviewQuestionDTO nextQuestion;
    private int currentIndex;
    private int totalQuestions;
}

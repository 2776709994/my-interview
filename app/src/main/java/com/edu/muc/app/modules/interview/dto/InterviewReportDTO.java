package com.edu.muc.app.modules.interview.dto;

import lombok.Data;
import java.util.List;

@Data
public class InterviewReportDTO {
    private String sessionId;
    private int totalQuestions;
    private int overallScore;
    private List<CategoryScoreDTO> categoryScores;
    private List<QuestionEvaluationDTO> questionDetails;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<ReferenceAnswerDTO> referenceAnswers;

    @Data
    public static class CategoryScoreDTO {
        private String category;
        private double score;
        private int questionCount;
    }

    @Data
    public static class QuestionEvaluationDTO {
        private int questionIndex;
        private String question;
        private String category;
        private String userAnswer;
        private double score;
        private String feedback;
    }

    @Data
    public static class ReferenceAnswerDTO {
        private int questionIndex;
        private String question;
        private String referenceAnswer;
        private List<String> keyPoints;
    }
}

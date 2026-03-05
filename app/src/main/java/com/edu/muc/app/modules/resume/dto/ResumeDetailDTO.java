package com.edu.muc.app.modules.resume.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ResumeDetailDTO {
    private Long id;
    private String filename;
    private Long fileSize;
    private String contentType;
    private String storageUrl;
    private LocalDateTime uploadedAt;
    private Integer accessCount;
    private String resumeText;
    private String analyzeStatus;
    private String analyzeError;

    // 分析历史列表
    private List<AnalysisDTO> analyses;

    // 面试历史列表（暂时为空）
    private List<Object> interviews = List.of();

    // 内部类：分析记录
    @Data
    public static class AnalysisDTO {
        private Long id;
        private Integer overallScore;
        private Integer contentScore;
        private Integer structureScore;
        private Integer skillMatchScore;
        private Integer expressionScore;
        private Integer projectScore;
        private String summary;
        private LocalDateTime analyzedAt;
        private List<String> strengths;
        private List<SuggestionDTO> suggestions;
    }

    // 内部类：建议对象
    @Data
    public static class SuggestionDTO {
        private String category;
        private String priority;
        private String issue;
        private String recommendation;
    }
}
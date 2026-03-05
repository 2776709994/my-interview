package com.edu.muc.app.modules.interview.dto;

import lombok.Data;
import java.util.List;

@Data
public class CreateInterviewRequest {
    private String resumeText;
    private int questionCount;
    private Long resumeId;
    private Boolean forceCreate;
    private String llmProvider;
    private String skillId;
    private String difficulty;
    private List<CategoryDTO> customCategories;
    private String jdText;

    @Data
    public static class CategoryDTO {
        private String key;
        private String label;
        private String priority;
    }
}

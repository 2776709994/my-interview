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
    /** 关联的知识库 ID 列表（打通 RAG：出题时检索知识库内容作为参考） */
    private List<Long> knowledgeBaseIds;

    @Data
    public static class CategoryDTO {
        private String key;
        private String label;
        private String priority;
    }
}

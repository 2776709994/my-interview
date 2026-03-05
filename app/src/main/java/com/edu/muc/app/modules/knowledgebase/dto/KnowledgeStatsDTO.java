package com.edu.muc.app.modules.knowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库统计信息 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeStatsDTO {
    private Integer totalCount = 0;
    private Integer completedCount = 0;
    private Integer processingCount = 0;
    private Integer failedCount = 0;
    private Integer totalQuestionCount = 0;
    private Integer totalAccessCount = 0;
}

package com.edu.muc.app.modules.knowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档列表项 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentDTO {
    private Long id;
    private String name;
    private String category;
    private String originalFilename;
    private Long fileSize;
    private String contentType;
    private LocalDateTime uploadedAt;
    private LocalDateTime lastAccessedAt;
    private Integer accessCount;
    private Integer questionCount;
    private String vectorStatus;
    private String vectorError;
    private Integer chunkCount;
}

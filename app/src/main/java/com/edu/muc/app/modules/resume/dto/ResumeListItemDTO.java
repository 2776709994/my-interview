package com.edu.muc.app.modules.resume.dto;

import lombok.Data;

import java.time.LocalDateTime;


@Data
public class ResumeListItemDTO {
    private Long id;
    private String filename;          // 对应前端的 filename
    private Long fileSize;
    private LocalDateTime uploadedAt;
    private Integer accessCount;
    private Integer latestScore;      // 最近一次分析的综合得分
    private LocalDateTime lastAnalyzedAt;
    private Integer interviewCount;
    private String analyzeStatus;
    private String analyzeError;

    // 全参构造函数
    public ResumeListItemDTO(Long id, String filename, Long fileSize,
                            LocalDateTime uploadedAt, Integer accessCount,
                            Integer latestScore, LocalDateTime lastAnalyzedAt,
                            Integer interviewCount, String analyzeStatus, String analyzeError) {
        this.id = id;
        this.filename = filename;
        this.fileSize = fileSize;
        this.uploadedAt = uploadedAt;
        this.accessCount = accessCount;
        this.latestScore = latestScore;
        this.lastAnalyzedAt = lastAnalyzedAt;
        this.interviewCount = interviewCount;
        this.analyzeStatus = analyzeStatus;
        this.analyzeError = analyzeError;
    }

    // getters & setters（或用 Lombok @Data）
}
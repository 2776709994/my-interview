package com.edu.muc.app.modules.resume.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 
 * @TableName resume_analyses
 */
@Data
@TableName("resume_analyses")
public class ResumeAnalyses {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 
     */
    private LocalDateTime analyzedAt;

    /**
     * 
     */
    private Integer contentScore;

    /**
     * 
     */
    private Integer expressionScore;

    /**
     * 
     */
    private Integer overallScore;

    /**
     * 
     */
    private Integer projectScore;

    /**
     * 
     */
    private Integer skillMatchScore;

    /**
     * 
     */
    private String strengthsJson;

    /**
     * 
     */
    private Integer structureScore;

    /**
     * 
     */
    private String suggestionsJson;

    /**
     * 
     */
    private String summary;

    /**
     * 
     */
    private Long resumeId;


}
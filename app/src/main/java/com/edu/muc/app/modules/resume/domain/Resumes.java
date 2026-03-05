package com.edu.muc.app.modules.resume.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 
 * @TableName resumes
 */
@Data
@TableName(value ="resumes")
public class Resumes {
    /**
     * 
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 
     */
    private Integer accessCount;

    /**
     * 
     */
    private String analyzeError;

    /**
     * 
     */
    private String analyzeStatus;

    /**
     * 
     */
    private String contentType;

    /**
     * 
     */
    private String fileHash;

    /**
     * 
     */
    private Long fileSize;

    /**
     * 
     */
    private Date lastAccessedAt;

    /**
     * 
     */
    private String originalFilename = "未命名简历";;

    /**
     * 
     */
    private String resumeText;

    /**
     * 
     */
    private String storageKey;

    /**
     * 
     */
    private String storageUrl;

    /**
     * 
     */
    private LocalDateTime uploadedAt;


}
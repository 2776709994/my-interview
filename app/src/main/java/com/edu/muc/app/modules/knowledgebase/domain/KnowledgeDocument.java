package com.edu.muc.app.modules.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体类
 * @TableName knowledge_documents
 */
@Data
@TableName(value = "knowledge_documents")
public class KnowledgeDocument {
    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文档名称（对齐前端 name 字段）
     */
    private String name;

    /**
     * 分类
     */
    private String category;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 解析后的文本内容
     */
    private String content;

    /**
     * 向量表示（1536 维）
     */
    @TableField("content_embedding")
    private String contentEmbedding;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME 类型
     */
    private String contentType;

    /**
     * MinIO 存储路径
     */
    private String storageKey;

    /**
     * MinIO 访问 URL
     */
    private String storageUrl;

    /**
     * 向量处理状态：PENDING, PROCESSING, COMPLETED, FAILED
     */
    private String vectorStatus;

    /**
     * 向量化错误信息
     */
    private String vectorError;

    /**
     * 分块数量
     */
    private Integer chunkCount = 1;

    /**
     * 提问次数
     */
    private Integer questionCount = 0;

    /**
     * 访问次数
     */
    private Integer accessCount = 0;

    /**
     * 上传时间
     */
    private LocalDateTime uploadedAt;

    /**
     * 处理完成时间
     */
    private LocalDateTime processedAt;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 父文档ID（用于分块关联，null表示是原始文档）
     */
    private Long parentId;

    /**
     * 分块索引（从0开始，-1表示未分块）
     */
    private Integer chunkIndex = -1;

    /**
     * 文件 MD5 哈希（用于上传查重）
     */
    private String fileHash;

    /**
     * 本次上传是否命中了重复文件（非数据库字段，仅用于接口响应）
     */
    @TableField(exist = false)
    private boolean duplicate;
}

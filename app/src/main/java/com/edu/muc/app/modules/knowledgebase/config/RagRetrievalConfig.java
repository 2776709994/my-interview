package com.edu.muc.app.modules.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalConfig {
    
    /**
     * 初始召回数量（从数据库检索的数量）
     */
    private int topK = 15;

    /**
     * 最终返回数量（经过过滤后返回给 AI 的数量）
     */
    private int finalTopK = 5;

    /**
     * 相似度阈值（余弦距离，越小越相似）
     * 范围：0-2，通常 0.3-0.5 之间
     */
    private double similarityThreshold = 0.3;
}

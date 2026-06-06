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

    /**
     * 关键词路召回数量（pg_trgm），与向量路 top-k 一致保持"Top-10 召回"口径
     */
    private int keywordTopK = 10;

    /**
     * Rerank 精排配置（gte-rerank-v2 交叉编码器，失败自动降级为 RRF 顺序）
     */
    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        /**
         * 是否启用重排
         */
        private boolean enabled = true;

        /**
         * DashScope Rerank REST 端点
         */
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        /**
         * 重排模型
         */
        private String model = "gte-rerank-v2";

        /**
         * 单次调用超时（秒）
         */
        private int timeoutSeconds = 5;
    }
}

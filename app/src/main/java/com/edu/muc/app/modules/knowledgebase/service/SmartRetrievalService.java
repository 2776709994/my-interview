package com.edu.muc.app.modules.knowledgebase.service;

import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;

import java.util.List;

/**
 * 智能检索服务
 */
public interface SmartRetrievalService {
    
    /**
     * 智能检索相关文档
     * @param queryVector 查询向量（JSON格式）
     * @return 过滤后的相关文档列表
     */
    List<KnowledgeDocument> smartRetrieve(String queryVector);

    /**
     * 智能检索相关文档（按知识库 ID 过滤）
     * @param queryVector 查询向量（JSON格式）
     * @param knowledgeBaseIds 知识库 ID 列表（关联子文档的 parent_id）
     * @return 过滤后的相关文档列表
     */
    List<KnowledgeDocument> smartRetrieve(String queryVector, List<Long> knowledgeBaseIds);
}

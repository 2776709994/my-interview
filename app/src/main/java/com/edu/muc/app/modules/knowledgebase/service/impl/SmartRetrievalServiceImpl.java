package com.edu.muc.app.modules.knowledgebase.service.impl;

import com.edu.muc.app.modules.knowledgebase.config.RagRetrievalConfig;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能检索服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartRetrievalServiceImpl implements SmartRetrievalService {

    private final KnowledgeDocumentMapper documentMapper;
    private final RagRetrievalConfig retrievalConfig;

    @Override
    public List<KnowledgeDocument> smartRetrieve(String queryVector) {
        return smartRetrieve(queryVector, null);
    }

    @Override
    public List<KnowledgeDocument> smartRetrieve(String queryVector, List<Long> knowledgeBaseIds) {
        // 1. 初始召回：检索 Top-K 个候选文档（按知识库 ID 过滤）
        int initialTopK = retrievalConfig.getTopK();
        List<Map<String, Object>> candidates = documentMapper.searchBySimilarityWithScoreAndKb(queryVector, initialTopK, knowledgeBaseIds);
        
        log.info("🔍 初始召回 {} 个候选文档", candidates.size());
        
        if (candidates.isEmpty()) {
            return List.of();
        }
        
        // 2. 过滤低相似度文档
        double threshold = retrievalConfig.getSimilarityThreshold();
        List<Map<String, Object>> filtered = candidates.stream()
                .filter(doc -> {
                    Double score = (Double) doc.get("similarity_score");
                    return score != null && score <= threshold;
                })
                .collect(Collectors.toList());
        
        log.info("✅ 过滤后剩余 {} 个相关文档（阈值: {}）", filtered.size(), threshold);
        
        if (filtered.isEmpty()) {
            // 如果全部被过滤，返回最相似的 1 个
            log.warn("⚠️ 所有文档相似度都低于阈值，返回最相似的 1 个");
            filtered = candidates.subList(0, 1);
        }
        
        // 3. 截取最终返回数量
        int finalTopK = Math.min(retrievalConfig.getFinalTopK(), filtered.size());
        List<Map<String, Object>> finalResults = filtered.subList(0, finalTopK);
        
        log.info("📦 最终返回 {} 个文档", finalResults.size());
        
        // 4. 转换为 KnowledgeDocument 对象
        return convertToDocuments(finalResults);
    }
    
    /**
     * 将 Map 列表转换为 KnowledgeDocument 列表
     */
    private List<KnowledgeDocument> convertToDocuments(List<Map<String, Object>> maps) {
        List<KnowledgeDocument> documents = new ArrayList<>();
        
        for (Map<String, Object> map : maps) {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(((Number) map.get("id")).longValue());
            doc.setName((String) map.get("name"));
            doc.setCategory((String) map.get("category"));
            doc.setFileName((String) map.get("file_name"));
            doc.setContent((String) map.get("content"));
            doc.setContentEmbedding((String) map.get("content_embedding"));
            
            Object fileSizeObj = map.get("file_size");
            if (fileSizeObj instanceof Number) {
                doc.setFileSize(((Number) fileSizeObj).longValue());
            }
            
            doc.setContentType((String) map.get("content_type"));
            doc.setStorageKey((String) map.get("storage_key"));
            doc.setStorageUrl((String) map.get("storage_url"));
            doc.setVectorStatus((String) map.get("vector_status"));
            doc.setVectorError((String) map.get("vector_error"));
            
            Object chunkCountObj = map.get("chunk_count");
            if (chunkCountObj instanceof Number) {
                doc.setChunkCount(((Number) chunkCountObj).intValue());
            }
            
            Object questionCountObj = map.get("question_count");
            if (questionCountObj instanceof Number) {
                doc.setQuestionCount(((Number) questionCountObj).intValue());
            }
            
            Object accessCountObj = map.get("access_count");
            if (accessCountObj instanceof Number) {
                doc.setAccessCount(((Number) accessCountObj).intValue());
            }
            
            Object parentIdObj = map.get("parent_id");
            if (parentIdObj instanceof Number) {
                doc.setParentId(((Number) parentIdObj).longValue());
            }
            
            Object chunkIndexObj = map.get("chunk_index");
            if (chunkIndexObj instanceof Number) {
                doc.setChunkIndex(((Number) chunkIndexObj).intValue());
            }
            
            // 记录相似度分数（用于调试）
            Double score = (Double) map.get("similarity_score");
            if (score != null) {
                log.debug("  - 文档 {}: 相似度距离 = {}", doc.getId(), String.format("%.4f", score));
            }
            
            documents.add(doc);
        }
        
        return documents;
    }
}

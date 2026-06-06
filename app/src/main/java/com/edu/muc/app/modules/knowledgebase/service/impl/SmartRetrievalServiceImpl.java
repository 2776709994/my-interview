package com.edu.muc.app.modules.knowledgebase.service.impl;

import com.edu.muc.app.modules.knowledgebase.config.RagRetrievalConfig;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能检索服务实现。
 * <p>
 * 检索链路（与简历口径一致）：
 * <pre>
 *   双路 Top-10 召回（pgvector 向量路 + pg_trgm 关键词路）
 *     → RRF 倒排融合（1/(60+rank)，只看排名不看分数）
 *     → 余弦距离 ≤0.3 阈值粗过滤（仅关键词路命中的文档视为强信号保留）
 *     → gte-rerank-v2 交叉编码精排（可选，失败自动降级为 RRF 顺序）
 *     → Top-5 截断返回
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartRetrievalServiceImpl implements SmartRetrievalService {

    private final KnowledgeDocumentMapper documentMapper;
    private final RagRetrievalConfig retrievalConfig;
    private final DashScopeRerankClient rerankClient;

    /** RRF 融合常数（业界标准值 60） */
    private static final int RRF_K = 60;

    /** 关键词路查询文本最大长度（截断防止超长文本无子串命中） */
    private static final int KEYWORD_MAX_LEN = 50;

    @Override
    public List<KnowledgeDocument> smartRetrieve(String queryVector) {
        return smartRetrieve(null, queryVector, null);
    }

    @Override
    public List<KnowledgeDocument> smartRetrieve(String queryVector, List<Long> knowledgeBaseIds) {
        return smartRetrieve(null, queryVector, knowledgeBaseIds);
    }

    @Override
    public List<KnowledgeDocument> smartRetrieve(String queryText, String queryVector, List<Long> knowledgeBaseIds) {
        int initialTopK = retrievalConfig.getTopK();
        double threshold = retrievalConfig.getSimilarityThreshold();
        int finalTopK = retrievalConfig.getFinalTopK();

        // ========== 1. 双路召回 ==========
        // 1a. 向量路：Top-K 候选（按知识库 ID 过滤）
        List<Map<String, Object>> vectorCandidates = documentMapper
                .searchBySimilarityWithScoreAndKb(queryVector, initialTopK, knowledgeBaseIds);
        log.info("🔍 向量路召回 {} 个候选", vectorCandidates.size());

        // 1b. 关键词路：pg_trgm 关键词召回（queryText 为空时跳过，兼容旧调用）
        List<Map<String, Object>> keywordCandidates = new ArrayList<>();
        if (queryText != null && queryText.trim().length() >= 2) {
            String keyword = queryText.trim();
            if (keyword.length() > KEYWORD_MAX_LEN) {
                keyword = keyword.substring(0, KEYWORD_MAX_LEN);
            }
            keywordCandidates = documentMapper.searchByKeyword(keyword, initialTopK, knowledgeBaseIds);
            log.info("🔍 关键词路召回 {} 个候选", keywordCandidates.size());
        }

        if (vectorCandidates.isEmpty() && keywordCandidates.isEmpty()) {
            return List.of();
        }

        // ========== 2. RRF 融合 ==========
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        Map<Long, Integer> vectorRank = new HashMap<>();
        Map<Long, Integer> keywordRank = new HashMap<>();

        int rank = 1;
        for (Map<String, Object> doc : vectorCandidates) {
            if (doc.get("id") instanceof Number) {
                long docId = ((Number) doc.get("id")).longValue();
                vectorRank.put(docId, rank++);
                merged.putIfAbsent(docId, doc);
            }
        }
        rank = 1;
        for (Map<String, Object> doc : keywordCandidates) {
            if (doc.get("id") instanceof Number) {
                long docId = ((Number) doc.get("id")).longValue();
                keywordRank.put(docId, rank++);
                merged.putIfAbsent(docId, doc);
            }
        }

        Map<Long, Double> rrfScores = new HashMap<>();
        for (Long docId : merged.keySet()) {
            double score = 0;
            if (vectorRank.containsKey(docId)) {
                score += 1.0 / (RRF_K + vectorRank.get(docId));
            }
            if (keywordRank.containsKey(docId)) {
                score += 1.0 / (RRF_K + keywordRank.get(docId));
            }
            rrfScores.put(docId, score);
        }

        List<Map<String, Object>> rrfOrdered = merged.keySet().stream()
                .sorted(Comparator.comparingDouble(rrfScores::get).reversed())
                .map(merged::get)
                .collect(Collectors.toList());
        log.info("🔀 RRF 融合后共 {} 条候选", rrfOrdered.size());

        // ========== 3. 阈值粗过滤 ==========
        // 向量路命中的文档要求余弦距离 <= 阈值；仅关键词路命中（无向量距离）的文档视为强信号保留
        List<Map<String, Object>> filtered = rrfOrdered.stream()
                .filter(doc -> {
                    Object distObj = doc.get("similarity_score");
                    if (!(distObj instanceof Number)) {
                        return true; // 仅关键词路命中
                    }
                    return ((Number) distObj).doubleValue() <= threshold;
                })
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            // 全部被过滤时，兜底返回最相似的 1 条（RRF 第一名）
            log.warn("⚠️ 所有文档相似度都低于阈值，返回最相似的 1 个");
            filtered = rrfOrdered.subList(0, Math.min(1, rrfOrdered.size()));
        }
        log.info("✅ 过滤后剩余 {} 条候选（阈值: {}）", filtered.size(), threshold);

        // ========== 4. Rerank 精排（可选，失败自动降级为 RRF 顺序） ==========
        List<Map<String, Object>> ranked;
        if (retrievalConfig.getRerank().isEnabled()
                && queryText != null && !queryText.isBlank()
                && !filtered.isEmpty()) {
            List<String> contents = filtered.stream()
                    .map(doc -> String.valueOf(doc.get("content")))
                    .collect(Collectors.toList());
            List<Integer> order = rerankClient.rerank(
                    retrievalConfig.getRerank().getUrl(),
                    retrievalConfig.getRerank().getModel(),
                    queryText,
                    contents,
                    Math.min(finalTopK, contents.size()));
            if (order != null) {
                List<Map<String, Object>> reranked = new ArrayList<>();
                for (Integer idx : order) {
                    if (idx >= 0 && idx < filtered.size()) {
                        reranked.add(filtered.get(idx));
                    }
                }
                ranked = reranked;
                log.info("🎯 Rerank 精排完成，候选 {} 条", ranked.size());
            } else {
                log.info("♻️ Rerank 不可用，降级为 RRF 融合顺序");
                ranked = filtered;
            }
        } else {
            ranked = filtered;
        }

        // ========== 5. 截取最终返回数量 ==========
        int finalTopKCount = Math.min(finalTopK, ranked.size());
        List<Map<String, Object>> finalResults = ranked.subList(0, finalTopKCount);
        log.info("📦 最终返回 {} 个文档", finalResults.size());

        // ========== 6. 转换实体 ==========
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
            Object scoreObj = map.get("similarity_score");
            if (scoreObj instanceof Number) {
                log.debug("  - 文档 {}: 相似度距离 = {}", doc.getId(), ((Number) scoreObj).doubleValue());
            }

            documents.add(doc);
        }
        return documents;
    }
}

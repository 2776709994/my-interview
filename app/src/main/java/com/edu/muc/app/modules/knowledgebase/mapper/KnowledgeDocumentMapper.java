package com.edu.muc.app.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 知识文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 向量相似度检索（旧方法，保留兼容）
     * @param embedding 查询向量（JSON 数组格式）
     * @param limit 返回数量
     * @return 相似度最高的文档列表
     */
    @Select("SELECT id, name, category, file_name, content, content_embedding, file_size, content_type, storage_key, storage_url, vector_status, vector_error, chunk_count, question_count, access_count, uploaded_at, processed_at, last_accessed_at, parent_id, chunk_index " +
            "FROM knowledge_documents " +
            "WHERE vector_status = 'COMPLETED' AND parent_id IS NOT NULL " +
            "ORDER BY content_embedding <=> #{embedding}::vector " +
            "LIMIT #{limit}")
    List<KnowledgeDocument> searchBySimilarity(@Param("embedding") String embedding, @Param("limit") int limit);

    /**
     * 向量相似度检索（带分数）
     * @param embedding 查询向量（JSON 数组格式）
     * @param limit 返回数量
     * @return 包含相似度分数的 Map 列表
     */
    List<Map<String, Object>> searchBySimilarityWithScore(@Param("queryVector") String embedding, @Param("limit") int limit);

    /**
     * 向量相似度检索（带分数，按知识库 ID 过滤）
     * @param queryVector 查询向量（JSON 数组格式）
     * @param limit 返回数量
     * @param knowledgeBaseIds 知识库 ID 列表（匹配 parent_id）
     * @return 包含相似度分数的 Map 列表
     */
    List<Map<String, Object>> searchBySimilarityWithScoreAndKb(@Param("queryVector") String queryVector,
                                                               @Param("limit") int limit,
                                                               @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds);

    /**
     * 自定义插入，处理 vector 类型转换
     */
    int insertVectorDocument(KnowledgeDocument document);
}

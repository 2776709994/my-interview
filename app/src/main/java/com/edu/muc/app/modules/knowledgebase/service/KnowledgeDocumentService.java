package com.edu.muc.app.modules.knowledgebase.service;

import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeStatsDTO;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 知识文档服务接口
 */
public interface KnowledgeDocumentService {

    /**
     * 上传知识文档
     * @param file 文件
     * @param name 自定义名称（可选）
     * @param category 分类（可选）
     * @return 文档记录
     */
    KnowledgeDocument upload(MultipartFile file, String name, String category) throws Exception;

    /**
     * 获取文档列表
     * @param sortBy 排序方式：time, size, access, question
     * @param vectorStatus 向量状态筛选
     * @return 文档列表
     */
    List<KnowledgeDocumentDTO> getList(String sortBy, String vectorStatus);
    
    /**
     * 搜索文档
     * @param keyword 关键词
     * @return 文档列表
     */
    List<KnowledgeDocumentDTO> search(String keyword);

    /**
     * 删除文档
     * @param id 文档 ID
     * @return 是否成功
     */
    boolean delete(Long id);

    /**
     * 获取统计信息
     * @return 统计数据
     */
    KnowledgeStatsDTO getStatistics();

    /**
     * 获取所有分类
     * @return 分类列表
     */
    List<String> getCategories();

    /**
     * 更新文档分类
     * @param id 文档 ID
     * @param category 新分类
     * @return 是否成功
     */
    boolean updateCategory(Long id, String category);

    /**
     * 流式 RAG 查询
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question 问题
     * @return SSE 发射器
     */
    SseEmitter queryStream(List<Long> knowledgeBaseIds, String question);
    
    /**
     * 重新向量化（手动重试）
     * @param id 文档 ID
     */
    void revectorize(Long id);

    /**
     * 对父文档执行向量化：分块 → Embedding → 子文档入库（由向量消费者异步调用）
     */
    void vectorizeDocument(Long parentId);
}

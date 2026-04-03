package com.edu.muc.app.modules.knowledgebase.controller;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeStatsDTO;
import com.edu.muc.app.modules.knowledgebase.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledgebase")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传知识文档
     */
    @PostMapping("/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam(value = "name", required = false) String name,
                            @RequestParam(value = "category", required = false) String category) throws Exception {
        log.info("📤 开始上传知识文档: {}", file.getOriginalFilename());
        KnowledgeDocument document = documentService.upload(file, name, category);

        // 构建前端期望的返回结构
        Map<String, Object> resp = new HashMap<>();

        Map<String, Object> kb = new HashMap<>();
        kb.put("id", document.getId());
        kb.put("name", document.getName());
        kb.put("category", document.getCategory() != null ? document.getCategory() : "");
        kb.put("fileSize", document.getFileSize());
        kb.put("contentLength", document.getContent() != null ? document.getContent().length() : 0);

        Map<String, Object> storage = new HashMap<>();
        storage.put("fileKey", document.getStorageKey());
        storage.put("fileUrl", document.getStorageUrl());

        resp.put("knowledgeBase", kb);
        resp.put("storage", storage);
        resp.put("duplicate", false);

        return Result.success(resp);
    }

    /**
     * 获取文档列表
     */
    @GetMapping("/list")
    public Result<List<KnowledgeDocumentDTO>> list(@RequestParam(required = false) String sortBy,
                                                    @RequestParam(required = false) String vectorStatus) {
        List<KnowledgeDocumentDTO> list = documentService.getList(sortBy, vectorStatus);
        return Result.success(list);
    }

    /**
     * 搜索文档
     */
    @GetMapping("/search")
    public Result<List<KnowledgeDocumentDTO>> search(@RequestParam String keyword) {
        List<KnowledgeDocumentDTO> list = documentService.search(keyword);
        return Result.success(list);
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public Result<KnowledgeStatsDTO> stats() {
        KnowledgeStatsDTO stats = documentService.getStatistics();
        return Result.success(stats);
    }

    /**
     * 获取所有分类
     */
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        List<String> categories = documentService.getCategories();
        return Result.success(categories);
    }

    /**
     * 更新文档分类
     */
    @PutMapping("/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        boolean success = documentService.updateCategory(id, body.get("category"));
        if (success) {
            return Result.success();
        } else {
            return Result.error("更新失败");
        }
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        boolean success = documentService.delete(id);
        if (success) {
            return Result.success();
        } else {
            return Result.error("文档不存在");
        }
    }

    /**
     * 流式 RAG 查询
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<Long> knowledgeBaseIds = (List<Long>) req.get("knowledgeBaseIds");
        String question = (String) req.get("question");

        log.info("🔍 开始 RAG 流式查询，问题: {}, 知识库 IDs: {}", question, knowledgeBaseIds);
        return documentService.queryStream(knowledgeBaseIds, question);
    }

    /**
     * 重新向量化（手动重试）
     */
    @PostMapping("/{id}/revectorize")
    public Result<Void> revectorize(@PathVariable Long id) {
        documentService.revectorize(id);
        return Result.success();
    }
}

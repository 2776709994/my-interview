package com.edu.muc.app.modules.knowledgebase.controller;

import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.Result;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeStatsDTO;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.KnowledgeDocumentService;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import com.edu.muc.app.modules.knowledgebase.service.impl.EmbeddingCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledgebase")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeDocumentService documentService;
    private final SmartRetrievalService smartRetrievalService;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final KnowledgeDocumentMapper documentMapper;
    private final EmbeddingCacheService embeddingCacheService;

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
        resp.put("duplicate", document.isDuplicate());

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
        // 类型安全检查：knowledgeBaseIds
        Object kbIdsObj = req.get("knowledgeBaseIds");
        List<Long> knowledgeBaseIds = List.of();
        if (kbIdsObj == null) {
            log.warn("knowledgeBaseIds 为空，将检索全部知识库");
        } else if (kbIdsObj instanceof List<?> rawList) {
            try {
                knowledgeBaseIds = rawList.stream()
                        .map(obj -> {
                            if (obj instanceof Number n) {
                                return n.longValue();
                            }
                            throw new IllegalArgumentException("knowledgeBaseIds 包含非数字元素: " + obj);
                        })
                        .toList();
            } catch (IllegalArgumentException e) {
                throw new BusinessException("VALIDATION_ERROR", "knowledgeBaseIds 类型不正确: " + e.getMessage());
            }
        } else {
            throw new BusinessException("VALIDATION_ERROR", "knowledgeBaseIds 类型不正确，期望 List");
        }

        // 问题输入校验
        String question = req.get("question") != null ? (String) req.get("question") : "";
        if (question.trim().isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "提问内容不能为空");
        }
        if (question.length() > 2000) {
            throw new BusinessException("VALIDATION_ERROR", "提问内容不能超过2000字");
        }
        String sanitized = question.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        log.info("🔍 开始 RAG 流式查询，问题: {}, 知识库 IDs: {}", sanitized, knowledgeBaseIds);
        return documentService.queryStream(knowledgeBaseIds, sanitized);
    }

    /**
     * 重新向量化（手动重试）
     */
    @PostMapping("/{id}/revectorize")
    public Result<Void> revectorize(@PathVariable Long id) {
        documentService.revectorize(id);
        return Result.success();
    }

    /**
     * 获取文档完整内容（用于评估脚本）
     */
    @GetMapping("/{id}/content")
    public Result<KnowledgeDocument> getDocumentContent(@PathVariable Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            return Result.error("文档不存在");
        }
        // 只允许获取父文档，排除 chunk 子文档
        if (doc.getParentId() != null) {
            return Result.error("只能获取父文档");
        }
        return Result.success(doc);
    }

    /**
     * 非流式 RAG 查询（用于评估脚本，不持久化消息）
     */
    @PostMapping("/query")
    public Result<EvalQueryResponse> query(@RequestBody Map<String, Object> req) {
        String question = req.get("question") != null ? (String) req.get("question") : "";
        if (question.trim().isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "提问内容不能为空");
        }
        if (question.length() > 2000) {
            throw new BusinessException("VALIDATION_ERROR", "提问内容不能超过2000字");
        }

        // 1. 将问题向量化（高频问题走 Redis 缓存，命中跳过 embedding 调用）
        float[] qEmbedding = embeddingCacheService.embedCached(embeddingModel, question);
        String qJson = JsonUtils.convertEmbeddingToJson(qEmbedding);

        // 2. 智能检索相关文档（双路检索：向量 + 关键词 + Rerank）
        List<KnowledgeDocument> docs = smartRetrievalService.smartRetrieve(question, qJson, null);

        // 3. 构建上下文
        List<String> contextList = docs.stream()
                .map(doc -> String.format("【%s】\n%s", doc.getName(), doc.getContent()))
                .collect(Collectors.toList());
        String context = String.join("\n\n", contextList);

        // 4. 非流式调用 AI
        String sysPrompt = "你是一个专业的知识库助手。请根据提供的上下文信息回答问题。如果上下文中没有相关信息，请诚实地告诉用户。回答时尽量引用具体的文档来源。";
        String userPrompt = String.format("问题：%s\n\n上下文：\n%s", question, context);
        String answer = chatClient.prompt()
                .system(sysPrompt)
                .user(userPrompt)
                .call()
                .content();

        // 5. 组装响应
        List<Map<String, String>> contexts = docs.stream().map(doc -> {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("content", String.format("【%s】\n%s", doc.getName(), doc.getContent()));
            return ctx;
        }).collect(Collectors.toList());

        return Result.success(new EvalQueryResponse(answer, contexts));
    }

    /**
     * 非流式查询响应 DTO
     */
    private record EvalQueryResponse(String answer, List<Map<String, String>> contexts) {}
}

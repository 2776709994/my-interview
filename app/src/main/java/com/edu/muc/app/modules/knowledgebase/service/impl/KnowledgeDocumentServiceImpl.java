package com.edu.muc.app.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.infrastructure.file.DocumentParseService;
import com.edu.muc.app.infrastructure.file.FileHashService;
import com.edu.muc.app.modules.knowledgebase.listener.VectorizeStreamProducer;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.infrastructure.file.FileStorageService;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import com.edu.muc.app.modules.knowledgebase.dto.KnowledgeStatsDTO;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.KnowledgeDocumentService;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 知识文档服务实现
 */
@Slf4j
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final FileStorageService fileStorageService;
    private final EmbeddingModel embeddingModel;
    private final DocumentParseService documentParseService;
    private final FileHashService fileHashService;
    private final ChatClient chatClient;
    private final ExecutorService executorService;
    private final SmartRetrievalService smartRetrievalService;
    private final EmbeddingCacheService embeddingCacheService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper documentMapper,
                                        FileStorageService fileStorageService,
                                        EmbeddingModel embeddingModel,
                                        DocumentParseService documentParseService,
                                        FileHashService fileHashService,
                                        ChatClient chatClient,
                                        @org.springframework.beans.factory.annotation.Qualifier("ragQueryExecutor") 
                                        ExecutorService executorService,
                                        SmartRetrievalService smartRetrievalService,
                                        EmbeddingCacheService embeddingCacheService,
                                        VectorizeStreamProducer vectorizeStreamProducer) {
        this.documentMapper = documentMapper;
        this.fileStorageService = fileStorageService;
        this.embeddingModel = embeddingModel;
        this.documentParseService = documentParseService;
        this.fileHashService = fileHashService;
        this.chatClient = chatClient;
        this.executorService = executorService;
        this.smartRetrievalService = smartRetrievalService;
        this.embeddingCacheService = embeddingCacheService;
        this.vectorizeStreamProducer = vectorizeStreamProducer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument upload(MultipartFile file, String name, String category) throws Exception {
        String storageKey = null;
        try {
            // 1. 计算 SHA-256 查重（内容寻址，抗碰撞性优于 MD5）
            String hash = fileHashService.calculateHash(file);
            log.info("🔍 文件 SHA-256: {}", hash);

            // 1.1 查重：内容相同的文件直接返回已有父文档记录，不再重复解析与向量化
            KnowledgeDocument existing = documentMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getFileHash, hash)
                            .isNull(KnowledgeDocument::getParentId)
                            .last("LIMIT 1")
            );
            if (existing != null) {
                log.info("♻️ 知识库已存在相同文件（SHA-256: {}），返回已有记录 id={}", hash, existing.getId());
                existing.setDuplicate(true);
                return existing;
            }

            // 2. 上传到 MinIO（使用 knowledge-base 目录）
            storageKey = fileStorageService.store(file, "knowledge-base");
            String storageUrl = fileStorageService.getFileUrl(storageKey);
            log.info("✅ 文件已上传到 MinIO: {}", storageKey);

            // 3. 使用专业文档解析（PDF 禁用图片提取/按坐标排序、DOCX 禁用嵌入资源、正则清洗噪声）
            String content = documentParseService.parseContent(file);
            log.info("✅ 文件解析成功，内容长度: {}", content.length());

            // 5. 创建父文档记录（存储完整内容和元数据）
            KnowledgeDocument parentDoc = new KnowledgeDocument();
            parentDoc.setName(name != null ? name : extractTitle(file.getOriginalFilename()));
            parentDoc.setCategory(category);
            parentDoc.setFileName(file.getOriginalFilename());
            parentDoc.setContent(content);
            parentDoc.setFileSize(file.getSize());
            parentDoc.setContentType(file.getContentType());
            parentDoc.setStorageKey(storageKey);
            parentDoc.setStorageUrl(storageUrl);
            parentDoc.setFileHash(hash);
            parentDoc.setVectorStatus("PENDING");
            parentDoc.setChunkCount(0);
            parentDoc.setQuestionCount(0);
            parentDoc.setAccessCount(0);
            parentDoc.setUploadedAt(LocalDateTime.now());
            parentDoc.setParentId(null);  // 父文档
            parentDoc.setChunkIndex(-1);   // 未分块标记

            // 插入父文档
            documentMapper.insert(parentDoc);
            Long parentId = parentDoc.getId();
            log.info("✅ 父文档入库成功，ID: {}", parentId);

            // 关键时序：必须在事务提交（afterCommit）后再入队，防止消费端读到未提交行
            // 分块 → Embedding → 子文档入库由向量消费者异步完成（接口入队即返回，前端轮询 vectorStatus）
            final Long docId = parentId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (!vectorizeStreamProducer.send(docId)) {
                        log.error("❌ 文档向量化任务入队失败（已标记 FAILED，可手动重新向量化）: {}", docId);
                    }
                }
            });

            return parentDoc;
        } catch (Exception e) {
            // 发生异常时，回滚数据库操作，并清理已上传的 MinIO 文件
            if (storageKey != null) {
                try {
                    fileStorageService.delete(storageKey);
                    log.warn("⚠️ 上传失败，已清理 MinIO 孤儿文件: {}", storageKey);
                } catch (Exception cleanupEx) {
                    log.error("❌ 清理 MinIO 孤儿文件失败: {}", storageKey, cleanupEx);
                }
            }
            log.error("❌ 知识文档上传失败", e);
            throw e;
        }
    }

    @Override
    public List<KnowledgeDocumentDTO> getList(String sortBy, String vectorStatus) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();

        // 只查询父文档（parentId 为 null 的记录）
        // 用 .apply 避免 MyBatis-Plus 对 null 字段调用 hashCode() 触发 NPE
        wrapper.apply("parent_id IS NULL");

        // 按状态筛选
        if (vectorStatus != null && !vectorStatus.isEmpty()) {
            wrapper.eq(KnowledgeDocument::getVectorStatus, vectorStatus);
        }

        // 排序（sortBy 可能为 null）
        if (sortBy != null && !sortBy.isEmpty()) {
            switch (sortBy) {
                case "time" -> wrapper.orderByDesc(KnowledgeDocument::getUploadedAt);
                case "size" -> wrapper.orderByDesc(KnowledgeDocument::getFileSize);
                case "access" -> wrapper.orderByDesc(KnowledgeDocument::getAccessCount);
                case "question" -> wrapper.orderByDesc(KnowledgeDocument::getQuestionCount);
                default -> wrapper.orderByDesc(KnowledgeDocument::getUploadedAt);
            }
        } else {
            wrapper.orderByDesc(KnowledgeDocument::getUploadedAt);
        }

        List<KnowledgeDocument> documents = documentMapper.selectList(wrapper);

        return documents.stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public List<KnowledgeDocumentDTO> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }
        
        String trimmedKeyword = keyword.trim();
        
        // 搜索父文档，匹配名称、分类或文件名
        List<KnowledgeDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .isNull(KnowledgeDocument::getParentId)
                        .and(wrapper -> wrapper
                                .like(KnowledgeDocument::getName, trimmedKeyword)
                                .or()
                                .like(KnowledgeDocument::getCategory, trimmedKeyword)
                                .or()
                                .like(KnowledgeDocument::getFileName, trimmedKeyword)
                        )
                        .orderByDesc(KnowledgeDocument::getUploadedAt)
        );
        
        log.info("🔍 搜索关键词: {}, 找到 {} 个结果", trimmedKeyword, documents.size());
        
        return documents.stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        KnowledgeDocument document = documentMapper.selectById(id);
        if (document == null) {
            return false;
        }

        // 如果是父文档，先删除所有子文档（分块）
        if (document.getParentId() == null) {
            documentMapper.delete(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getParentId, id)
            );
            log.info("✅ 已删除 {} 个子文档（分块）", document.getChunkCount());
        }

        // 删除数据库记录
        documentMapper.deleteById(id);

        // 删除 MinIO 文件（只有父文档有存储文件）
        try {
            if (document.getStorageKey() != null) {
                fileStorageService.delete(document.getStorageKey());
                log.info("✅ 已删除 MinIO 文件: {}", document.getStorageKey());
            }
        } catch (Exception e) {
            log.error("❌ 删除 MinIO 文件失败: {}", document.getStorageKey(), e);
        }

        return true;
    }

    @Override
    public KnowledgeStatsDTO getStatistics() {
        // 只统计父文档
        List<KnowledgeDocument> all = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .isNull(KnowledgeDocument::getParentId)
        );
        
        KnowledgeStatsDTO stats = new KnowledgeStatsDTO();
        stats.setTotalCount(all.size());
        stats.setCompletedCount((int) all.stream().filter(d -> "COMPLETED".equals(d.getVectorStatus())).count());
        stats.setProcessingCount((int) all.stream().filter(d -> "PROCESSING".equals(d.getVectorStatus())).count());
        stats.setFailedCount((int) all.stream().filter(d -> "FAILED".equals(d.getVectorStatus())).count());
        stats.setTotalQuestionCount(all.stream().mapToInt(d -> d.getQuestionCount() != null ? d.getQuestionCount() : 0).sum());
        stats.setTotalAccessCount(all.stream().mapToInt(d -> d.getAccessCount() != null ? d.getAccessCount() : 0).sum());
        
        return stats;
    }

    @Override
    public List<String> getCategories() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .isNull(KnowledgeDocument::getParentId)
        ).stream()
                .map(KnowledgeDocument::getCategory)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public boolean updateCategory(Long id, String category) {
        return documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .set(KnowledgeDocument::getCategory, category)
                .eq(KnowledgeDocument::getId, id)) > 0;
    }

    @Override
    public SseEmitter queryStream(List<Long> knowledgeBaseIds, String question) {
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 将问题向量化（高频问题走 Redis 缓存，命中跳过 embedding 调用）
                float[] qEmbedding = embeddingCacheService.embedCached(embeddingModel, question);
                String qJson = JsonUtils.convertEmbeddingToJson(qEmbedding);
                
                // 2. 智能检索相关文档片段（双路检索：向量 + 关键词 + Rerank，按请求指定的知识库 ID 过滤，空列表表示检索全部）
                List<KnowledgeDocument> docs = smartRetrievalService.smartRetrieve(question, qJson, knowledgeBaseIds);
                
                // 3. 构建上下文（使用 chunk 的内容）
                String context = docs.stream()
                        .map(doc -> {
                            // 标注来源：文档名 + 片段编号
                            String source = doc.getName();
                            return String.format("【%s】\n%s", source, doc.getContent());
                        })
                        .collect(Collectors.joining("\n\n"));
                
                log.info("🔍 RAG 检索到 {} 个相关文档片段", docs.size());
                
                // 4. 构建 Prompt
                String sysPrompt = "你是一个专业的知识库助手。请根据提供的上下文信息回答问题。如果上下文中没有相关信息，请诚实地告诉用户。回答时尽量引用具体的文档来源。";
                String userPrompt = String.format("问题：%s\n\n上下文：\n%s", question, context);

                // 5. 流式调用 AI
                final AtomicReference<org.reactivestreams.Subscription> subRef = new AtomicReference<>();
                chatClient.prompt()
                        .system(sysPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                log.error("❌ SSE 发送失败，关闭连接", e);
                                emitter.completeWithError(e);
                                org.reactivestreams.Subscription sub = subRef.get();
                                if (sub != null) sub.cancel();
                                throw new RuntimeException(e);
                            }
                        })
                        .doOnComplete(() -> {
                            // 更新父文档的提问次数和访问次数
                            if (!knowledgeBaseIds.isEmpty()) {
                                // 通过子文档的 parentId 找到父文档并更新统计
                                List<Long> parentIds = docs.stream()
                                        .map(KnowledgeDocument::getParentId)
                                        .filter(id -> id != null)
                                        .distinct()
                                        .toList();

                                if (!parentIds.isEmpty()) {
                                    documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                                            .setSql("question_count = question_count + 1")
                                            .in(KnowledgeDocument::getId, parentIds));
                                    documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                                            .setSql("access_count = access_count + 1")
                                            .in(KnowledgeDocument::getId, parentIds));
                                }
                            }
                            emitter.complete();
                        })
                        .doOnError(err -> {
                            log.error("❌ SSE 查询失败", err);
                            emitter.completeWithError(err);
                        })
                        .subscribe();
                        
            } catch (Exception e) {
                log.error("❌ SSE 查询异常", e);
                emitter.completeWithError(e);
            }
        }, executorService);
        
        return emitter;
    }

    /**
     * 从文件名提取标题（去掉扩展名）
     */
    private String extractTitle(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return fileName;
        }
        return fileName.substring(0, fileName.lastIndexOf("."));
    }

    @Override
    public void vectorizeDocument(Long parentId) {
        KnowledgeDocument document = documentMapper.selectById(parentId);
        if (document == null) {
            throw new IllegalStateException("知识文档不存在: " + parentId);
        }
        String content = document.getContent();
        if (content == null || content.isEmpty()) {
            throw new IllegalStateException("文档内容为空，无法向量化: " + parentId);
        }

        // 文本分块：每块约 800 字符，重叠 150 字符（约 19%）
        List<String> chunks = splitTextIntoChunks(content, 800);
        log.info("✅ 文本已分块，共 {} 块: kbId={}", chunks.size(), parentId);

        // 为每个 chunk 创建子文档并生成向量（Embedding 调用为 IO 密集型，运行在虚拟线程）
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            float[] embedding = embeddingModel.embed(chunk);
            String embeddingJson = JsonUtils.convertEmbeddingToJson(embedding);

            KnowledgeDocument chunkDoc = new KnowledgeDocument();
            chunkDoc.setName(document.getName() + " - 片段 " + (i + 1));
            chunkDoc.setCategory(document.getCategory());
            chunkDoc.setFileName(document.getFileName());
            chunkDoc.setContent(chunk);
            chunkDoc.setContentEmbedding(embeddingJson);
            chunkDoc.setFileSize(document.getFileSize());
            chunkDoc.setContentType(document.getContentType());
            chunkDoc.setStorageKey(document.getStorageKey());
            chunkDoc.setStorageUrl(document.getStorageUrl());
            chunkDoc.setVectorStatus("COMPLETED");
            chunkDoc.setChunkCount(1);
            chunkDoc.setQuestionCount(0);
            chunkDoc.setAccessCount(0);
            chunkDoc.setUploadedAt(LocalDateTime.now());
            chunkDoc.setProcessedAt(LocalDateTime.now());
            chunkDoc.setParentId(parentId);
            chunkDoc.setChunkIndex(i);

            documentMapper.insertVectorDocument(chunkDoc);
        }

        // 更新父文档分块数（终态 COMPLETED 由消费者 markCompleted 统一落库）
        document.setChunkCount(chunks.size());
        documentMapper.updateById(document);
        log.info("✅ 文档向量化处理完成，共 {} 个分块: kbId={}", chunks.size(), parentId);
    }


    /**
     * 智能文本分块（优化版）
     * @param text 原始文本
     * @param chunkSize 每块目标大小（字符数）
     * @return 分块列表
     */
    private List<String> splitTextIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 重叠大小（保留上下文）
        int overlapSize = Math.min(150, chunkSize / 5);  // 重叠约 150 字符
        
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            
            // 如果不是最后一块，尝试在句子边界处截断
            if (end < text.length()) {
                // 向后寻找最近的句子边界（最多扩展 100 字符）
                int searchEnd = Math.min(end + 100, text.length());
                int bestBreak = -1;
                
                // 优先级：换行 > 句号 > 空格
                for (int i = end; i < searchEnd; i++) {
                    char c = text.charAt(i);
                    if (c == '\n' || c == '。' || c == '.' || c == '！' || c == '？') {
                        bestBreak = i + 1;
                        break;
                    }
                }
                
                // 如果没找到句子边界，向前寻找
                if (bestBreak == -1) {
                    int searchStart = Math.max(start, end - 100);
                    for (int i = end; i > searchStart; i--) {
                        char c = text.charAt(i);
                        if (c == '\n' || c == '。' || c == '.' || c == ' ' || c == '，') {
                            bestBreak = i + 1;
                            break;
                        }
                    }
                }
                
                if (bestBreak != -1) {
                    end = bestBreak;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            
            // 下一块的起始位置（减去重叠部分）
            int nextStart = end - overlapSize;
            
            // 确保 start 在前进（防止死循环）
            if (nextStart <= start) {
                // 如果计算后的位置没有前进，说明块太小或重叠太大，直接跳到 end
                start = end;
            } else {
                start = nextStart;
            }
        }
        
        log.info("✅ 文本分块完成：总长度={}, 分块数={}, 平均长度={}", 
                text.length(), chunks.size(), 
                chunks.isEmpty() ? 0 : chunks.stream().mapToInt(String::length).sum() / chunks.size());
        
        return chunks;
    }


    /**
     * 实体转 DTO
     */
    private KnowledgeDocumentDTO toDTO(KnowledgeDocument doc) {
        KnowledgeDocumentDTO dto = new KnowledgeDocumentDTO();
        dto.setId(doc.getId());
        dto.setName(doc.getName());
        dto.setCategory(doc.getCategory());
        dto.setOriginalFilename(doc.getFileName());
        dto.setFileSize(doc.getFileSize());
        dto.setContentType(doc.getContentType());
        dto.setUploadedAt(doc.getUploadedAt());
        dto.setLastAccessedAt(doc.getLastAccessedAt());
        dto.setAccessCount(doc.getAccessCount());
        dto.setQuestionCount(doc.getQuestionCount());
        dto.setVectorStatus(doc.getVectorStatus());
        dto.setVectorError(doc.getVectorError());
        dto.setChunkCount(doc.getChunkCount());
        return dto;
    }

    @Override
    @Transactional
    public void revectorize(Long id) {
        KnowledgeDocument document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在");
        }
        
        // 只有父文档才能重新向量化
        if (document.getParentId() != null) {
            throw new BusinessException("INVALID_OPERATION", "只能对父文档进行重新向量化");
        }
        
        log.info("🔄 开始重新向量化文档: {}", id);

        // 1. 删除旧的子文档（分块）
        documentMapper.delete(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getParentId, id)
        );
        log.info("✅ 已删除旧的分块");

        // 2. 重置父文档状态为 PENDING（消费端状态守卫要求）
        document.setVectorStatus("PENDING");
        document.setVectorError(null);
        documentMapper.updateById(document);

        // 3. 事务提交后经 Redis Stream 异步向量化（自动重试 3 次，前端轮询 vectorStatus）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (!vectorizeStreamProducer.send(id)) {
                    log.error("❌ 重新向量化任务入队失败（已标记 FAILED）: {}", id);
                }
            }
        });
    }
}

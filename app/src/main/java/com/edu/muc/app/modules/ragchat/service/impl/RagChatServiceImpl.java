package com.edu.muc.app.modules.ragchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import com.edu.muc.app.modules.knowledgebase.service.impl.EmbeddingCacheService;
import com.edu.muc.app.modules.ragchat.domain.ChatMessage;
import com.edu.muc.app.modules.ragchat.domain.ChatSession;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionDetail;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionListItem;
import com.edu.muc.app.modules.ragchat.mapper.ChatMessageMapper;
import com.edu.muc.app.modules.ragchat.mapper.ChatSessionMapper;
import com.edu.muc.app.modules.ragchat.service.RagChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

@Slf4j
@Service

public class RagChatServiceImpl implements RagChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final SmartRetrievalService smartRetrievalService;
    private final EmbeddingCacheService embeddingCacheService;
    private final ExecutorService ragQueryExecutor;

    public RagChatServiceImpl(ChatSessionMapper sessionMapper,
                              ChatMessageMapper messageMapper,
                              KnowledgeDocumentMapper documentMapper,
                              ObjectMapper objectMapper,
                              EmbeddingModel embeddingModel,
                              ChatClient chatClient,
                              SmartRetrievalService smartRetrievalService,
                              EmbeddingCacheService embeddingCacheService,
                              @org.springframework.beans.factory.annotation.Qualifier("ragQueryExecutor") 
                              ExecutorService ragQueryExecutor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
        this.smartRetrievalService = smartRetrievalService;
        this.embeddingCacheService = embeddingCacheService;
        this.ragQueryExecutor = ragQueryExecutor;
    }

    @Override
    @Transactional
    public ChatSession createSession(List<Long> knowledgeBaseIds, String title) {
        try {
            ChatSession session = new ChatSession();
            session.setTitle(title != null ? title : "新对话");
            session.setKnowledgeBaseIds(objectMapper.writeValueAsString(knowledgeBaseIds));
            session.setIsPinned(false);
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            
            sessionMapper.insert(session);
            log.info("✅ 创建 RAG 聊天会话成功，ID: {}", session.getId());
            return session;
        } catch (JsonProcessingException e) {
            throw new BusinessException("SERIALIZATION_ERROR", "序列化知识库ID失败", e);
        }
    }

    @Override
    public List<RagChatSessionListItem> listSessions() {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .orderByDesc(ChatSession::getIsPinned)
                        .orderByDesc(ChatSession::getUpdatedAt)
        );

        if (sessions.isEmpty()) {
            return List.of();
        }

        // 批量查询所有会话的消息数（避免 N+1）
        List<Long> sessionIds = sessions.stream().map(ChatSession::getId).toList();
        List<ChatMessage> allMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .in(ChatMessage::getSessionId, sessionIds)
        );
        Map<Long, Long> messageCountMap = allMessages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getSessionId, Collectors.counting()));

        // 批量查询所有知识库ID对应的文档
        List<Long> allKbIds = new ArrayList<>();
        for (ChatSession session : sessions) {
            try {
                List<Long> kbIds = objectMapper.readValue(session.getKnowledgeBaseIds(),
                        new TypeReference<List<Long>>() {});
                allKbIds.addAll(kbIds);
            } catch (JsonProcessingException e) {
                log.warn("解析会话 {} 的知识库ID失败", session.getId());
            }
        }
        List<KnowledgeDocument> allDocs = allKbIds.isEmpty() ? List.of()
                : documentMapper.selectBatchIds(allKbIds);
        Map<Long, String> kbNameMap = allDocs.stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getName, (a, b) -> a));

        return sessions.stream().map(session -> {
            RagChatSessionListItem item = new RagChatSessionListItem();
            item.setId(session.getId());
            item.setTitle(session.getTitle());
            item.setIsPinned(session.getIsPinned());
            item.setUpdatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null);

            // 从预加载的 Map 中获取消息数
            Long count = messageCountMap.getOrDefault(session.getId(), 0L);
            item.setMessageCount(count.intValue());

            // 从预加载的 Map 中获取知识库名称
            try {
                List<Long> kbIds = objectMapper.readValue(session.getKnowledgeBaseIds(),
                        new TypeReference<List<Long>>() {});
                List<String> names = kbIds.stream()
                        .map(kbNameMap::get)
                        .filter(name -> name != null)
                        .toList();
                item.setKnowledgeBaseNames(names);
            } catch (JsonProcessingException e) {
                log.warn("解析会话 {} 的知识库ID失败", session.getId());
                item.setKnowledgeBaseNames(new ArrayList<>());
            }

            return item;
        }).collect(Collectors.toList());
    }

    @Override
    public RagChatSessionDetail getSessionDetail(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }

        RagChatSessionDetail detail = new RagChatSessionDetail();
        detail.setId(session.getId());
        detail.setTitle(session.getTitle());
        detail.setCreatedAt(session.getCreatedAt() != null ? session.getCreatedAt().toString() : null);
        detail.setUpdatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null);

        // 获取知识库列表
        try {
            List<Long> kbIds = objectMapper.readValue(
                    session.getKnowledgeBaseIds(), 
                    new TypeReference<List<Long>>() {}
            );
            if (!kbIds.isEmpty()) {
                List<KnowledgeDocument> docs = documentMapper.selectBatchIds(kbIds);
                detail.setKnowledgeBases(docs);
            } else {
                detail.setKnowledgeBases(new ArrayList<>());
            }
        } catch (JsonProcessingException e) {
            log.error("解析知识库ID失败", e);
            detail.setKnowledgeBases(new ArrayList<>());
        }

        // 获取消息列表
        List<ChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
        detail.setMessages(messages);

        return detail;
    }

    @Override
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        
        session.setTitle(title);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional
    public void updateKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        
        try {
            session.setKnowledgeBaseIds(objectMapper.writeValueAsString(knowledgeBaseIds));
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        } catch (JsonProcessingException e) {
            throw new BusinessException("SERIALIZATION_ERROR", "序列化知识库ID失败", e);
        }
    }

    @Override
    @Transactional
    public void togglePin(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        
        session.setIsPinned(!session.getIsPinned());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        // 删除会话
        sessionMapper.deleteById(sessionId);
        
        // 删除该会话的所有消息
        messageMapper.delete(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
        );
    }

    // 加载提示词模板（仅一次）
    private static final String SYSTEM_PROMPT_TEMPLATE;
    private static final String USER_PROMPT_TEMPLATE;
    static {
        try {
            ClassPathResource sysResource = new ClassPathResource("prompts/knowledgebase-query-system.st");
            SYSTEM_PROMPT_TEMPLATE = org.springframework.util.StreamUtils.copyToString(
                    sysResource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
            ClassPathResource userResource = new ClassPathResource("prompts/knowledgebase-query-user.st");
            USER_PROMPT_TEMPLATE = org.springframework.util.StreamUtils.copyToString(
                    userResource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("加载提示词模板失败", e);
        }
    }

    @Override
    public SseEmitter sendMessageStream(Long sessionId, String question) {
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        
        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setType("user");
        userMessage.setContent(question);
        userMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMessage);

        // 异步处理 AI 回答
        CompletableFuture.runAsync(() -> {
            try {
                ChatSession session = sessionMapper.selectById(sessionId);
                if (session == null) {
                    emitter.completeWithError(new RuntimeException("会话不存在"));
                    return;
                }

                // 解析知识库ID
                List<Long> knowledgeBaseIds = objectMapper.readValue(
                        session.getKnowledgeBaseIds(), 
                        new TypeReference<List<Long>>() {}
                );

                // 1. 将问题向量化（高频问题走 Redis 缓存，命中跳过 embedding 调用）
                float[] qEmbedding = embeddingCacheService.embedCached(embeddingModel, question);
                String qJson = JsonUtils.convertEmbeddingToJson(qEmbedding);
                
                // 2. 智能检索相关文档片段（双路检索：向量 + 关键词 + Rerank，按会话关联的知识库 ID 过滤）
                List<KnowledgeDocument> docs = smartRetrievalService.smartRetrieve(question, qJson, knowledgeBaseIds);
                
                // 3. 构建上下文（使用 chunk 的内容，并标注来源）
                String context = docs.stream()
                        .map(doc -> {
                            String source = doc.getName();
                            return String.format("【%s】\n%s", source, doc.getContent());
                        })
                        .collect(Collectors.joining("\n\n"));
                
                log.info("🔍 RAG 检索到 {} 个相关文档片段", docs.size());
                
                // 4. 使用模板构建 Prompt
                String userPrompt = USER_PROMPT_TEMPLATE.replace("{context}", context)
                        .replace("{question}", question);

                // 5. 流式调用 AI
                StringBuilder fullAnswer = new StringBuilder();
                final AtomicReference<org.reactivestreams.Subscription> subRef = new AtomicReference<>();
                chatClient.prompt()
                        .system(SYSTEM_PROMPT_TEMPLATE)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            try {
                                fullAnswer.append(chunk);
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
                            // 保存 AI 回答
                            ChatMessage assistantMessage = new ChatMessage();
                            assistantMessage.setSessionId(sessionId);
                            assistantMessage.setType("assistant");
                            assistantMessage.setContent(fullAnswer.toString());
                            assistantMessage.setCreatedAt(LocalDateTime.now());
                            messageMapper.insert(assistantMessage);

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

                            // 更新会话时间
                            session.setUpdatedAt(LocalDateTime.now());
                            sessionMapper.updateById(session);

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
        }, ragQueryExecutor);  // ✅ 使用配置的线程池
        
        return emitter;
    }
}

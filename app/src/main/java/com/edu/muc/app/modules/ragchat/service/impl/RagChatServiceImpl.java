package com.edu.muc.app.modules.ragchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.common.ai.SseTextStream;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
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
    private final TransactionTemplate transactionTemplate;

    public RagChatServiceImpl(ChatSessionMapper sessionMapper,
                              ChatMessageMapper messageMapper,
                              KnowledgeDocumentMapper documentMapper,
                              ObjectMapper objectMapper,
                              EmbeddingModel embeddingModel,
                              ChatClient chatClient,
                              SmartRetrievalService smartRetrievalService,
                              EmbeddingCacheService embeddingCacheService,
                              @org.springframework.beans.factory.annotation.Qualifier("ragQueryExecutor") 
                              ExecutorService ragQueryExecutor,
                              TransactionTemplate transactionTemplate) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
        this.smartRetrievalService = smartRetrievalService;
        this.embeddingCacheService = embeddingCacheService;
        this.ragQueryExecutor = ragQueryExecutor;
        this.transactionTemplate = transactionTemplate;
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
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在");
        }
        List<Long> knowledgeBaseIds = deserializeKnowledgeBaseIds(session);
        SseTextStream stream = new SseTextStream();
        
        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setType("user");
        userMessage.setContent(question);
        userMessage.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMessage);

        return stream.start(ragQueryExecutor, () -> {
            List<Message> history = loadRecentMessages(sessionId, userMessage.getId());
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
            stream.send(chatClient.prompt()
                    .system(SYSTEM_PROMPT_TEMPLATE)
                    .messages(history)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(fullAnswer::append), () -> transactionTemplate.executeWithoutResult(ignored -> {
                        if (fullAnswer.toString().isBlank()) {
                            throw new IllegalStateException("模型返回空回答");
                        }
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

                        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                                .eq(ChatSession::getId, sessionId)
                                .set(ChatSession::getUpdatedAt, LocalDateTime.now()));
                    }));
        });
    }

    private List<Message> loadRecentMessages(Long sessionId, Long beforeId) {
        List<ChatMessage> recent = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .lt(ChatMessage::getId, beforeId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT 10"));
        List<Message> history = new ArrayList<>(recent.size());
        for (ChatMessage entry : recent.reversed()) {
            if (entry.getContent() == null || entry.getContent().isBlank()) {
                continue;
            }
            String content = entry.getContent().substring(0, Math.min(1500, entry.getContent().length()));
            history.add("assistant".equals(entry.getType()) ? new AssistantMessage(content) : new UserMessage(content));
        }
        return history;
    }

    private List<Long> deserializeKnowledgeBaseIds(ChatSession session) {
        try {
            String value = session.getKnowledgeBaseIds();
            if (value == null || value.isBlank()) {
                return List.of();
            }
            List<Long> ids = objectMapper.readValue(value, new TypeReference<List<Long>>() {});
            return ids == null ? List.of() : ids;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取会话关联的知识库", error);
        }
    }
}

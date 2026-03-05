package com.edu.muc.app.modules.ragchat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatServiceImpl implements RagChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final SmartRetrievalService smartRetrievalService;

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
            throw new RuntimeException("序列化知识库ID失败", e);
        }
    }

    @Override
    public List<RagChatSessionListItem> listSessions() {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .orderByDesc(ChatSession::getIsPinned)
                        .orderByDesc(ChatSession::getUpdatedAt)
        );

        return sessions.stream().map(session -> {
            RagChatSessionListItem item = new RagChatSessionListItem();
            item.setId(session.getId());
            item.setTitle(session.getTitle());
            item.setIsPinned(session.getIsPinned());
            item.setUpdatedAt(session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null);

            // 统计消息数量
            Long count = messageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, session.getId())
            );
            item.setMessageCount(count.intValue());

            // 获取知识库名称
            try {
                List<Long> kbIds = objectMapper.readValue(
                        session.getKnowledgeBaseIds(), 
                        new TypeReference<List<Long>>() {}
                );
                if (!kbIds.isEmpty()) {
                    List<KnowledgeDocument> docs = documentMapper.selectBatchIds(kbIds);
                    List<String> names = docs.stream()
                            .map(KnowledgeDocument::getName)
                            .collect(Collectors.toList());
                    item.setKnowledgeBaseNames(names);
                } else {
                    item.setKnowledgeBaseNames(new ArrayList<>());
                }
            } catch (JsonProcessingException e) {
                log.error("解析知识库ID失败", e);
                item.setKnowledgeBaseNames(new ArrayList<>());
            }

            return item;
        }).collect(Collectors.toList());
    }

    @Override
    public RagChatSessionDetail getSessionDetail(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
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
            throw new RuntimeException("会话不存在");
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
            throw new RuntimeException("会话不存在");
        }
        
        try {
            session.setKnowledgeBaseIds(objectMapper.writeValueAsString(knowledgeBaseIds));
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化知识库ID失败", e);
        }
    }

    @Override
    @Transactional
    public void togglePin(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
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

                // 1. 将问题向量化
                float[] qEmbedding = embeddingModel.embed(question);
                String qJson = convertEmbeddingToJson(qEmbedding);
                
                // 2. 智能检索相关文档片段（带相似度过滤）
                List<KnowledgeDocument> docs = smartRetrievalService.smartRetrieve(qJson);
                
                // 3. 构建上下文（使用 chunk 的内容，并标注来源）
                String context = docs.stream()
                        .map(doc -> {
                            String source = doc.getName();
                            return String.format("【%s】\n%s", source, doc.getContent());
                        })
                        .collect(Collectors.joining("\n\n"));
                
                log.info("🔍 RAG 检索到 {} 个相关文档片段", docs.size());
                
                // 4. 构建 Prompt
                String sysPrompt = "你是一个专业的知识库助手。请根据提供的上下文信息回答问题。如果上下文中没有相关信息，请诚实地告诉用户。回答时尽量引用具体的文档来源。";
                String userPrompt = String.format("问题：%s\n\n上下文：\n%s", question, context);

                // 5. 流式调用 AI
                StringBuilder fullAnswer = new StringBuilder();
                chatClient.prompt()
                        .system(sysPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            try { 
                                fullAnswer.append(chunk);
                                emitter.send(chunk); 
                            } catch (IOException e) { 
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
        }, java.util.concurrent.Executors.newCachedThreadPool());
        
        return emitter;
    }

    /**
     * 将向量数组转换为 JSON 字符串
     */
    private String convertEmbeddingToJson(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}

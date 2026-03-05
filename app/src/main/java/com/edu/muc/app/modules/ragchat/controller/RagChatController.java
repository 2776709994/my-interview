package com.edu.muc.app.modules.ragchat.controller;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.modules.ragchat.domain.ChatSession;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionDetail;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionListItem;
import com.edu.muc.app.modules.ragchat.service.RagChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag-chat")
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatService ragChatService;
    private final ObjectMapper objectMapper;

    /**
     * 获取聊天会话列表
     */
    @GetMapping("/sessions")
    public Result<List<RagChatSessionListItem>> getSessions() {
        List<RagChatSessionListItem> sessions = ragChatService.listSessions();
        return Result.success(sessions);
    }

    /**
     * 创建聊天会话
     */
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession(@RequestBody Map<String, Object> req) {
        String title = (String) req.getOrDefault("title", "新对话");
        
        // 处理 knowledgeBaseIds，它可能是一个 List
        Object kbIdsObj = req.get("knowledgeBaseIds");
        List<Long> knowledgeBaseIds = List.of();
        if (kbIdsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> rawList = (List<Object>) kbIdsObj;
            knowledgeBaseIds = rawList.stream()
                    .map(obj -> ((Number) obj).longValue())
                    .toList();
        }

        ChatSession session = ragChatService.createSession(knowledgeBaseIds, title);

        return Result.success(Map.of(
                "id", session.getId(),
                "title", session.getTitle(),
                "createdAt", session.getCreatedAt().toString()
        ));
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<RagChatSessionDetail> getSessionDetail(@PathVariable Long sessionId) {
        RagChatSessionDetail detail = ragChatService.getSessionDetail(sessionId);
        return Result.success(detail);
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(@PathVariable Long sessionId, @RequestBody Map<String, String> req) {
        String title = req.get("title");
        ragChatService.updateSessionTitle(sessionId, title);
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateKnowledgeBases(@PathVariable Long sessionId, @RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<Object> rawList = (List<Object>) req.get("knowledgeBaseIds");
        List<Long> knowledgeBaseIds = rawList.stream()
                .map(obj -> ((Number) obj).longValue())
                .toList();
        ragChatService.updateKnowledgeBases(sessionId, knowledgeBaseIds);
        return Result.success(null);
    }

    /**
     * 切换置顶状态
     */
    @PutMapping("/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        ragChatService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        ragChatService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 流式问答
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable Long sessionId, @RequestBody Map<String, String> req) {
        String question = req.get("question");
        log.info("🔍 开始 RAG 流式问答，会话ID: {}, 问题: {}", sessionId, question);
        return ragChatService.sendMessageStream(sessionId, question);
    }
}

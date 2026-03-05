package com.edu.muc.app.modules.ragchat.service;

import com.edu.muc.app.modules.ragchat.domain.ChatSession;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionDetail;
import com.edu.muc.app.modules.ragchat.dto.RagChatSessionListItem;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface RagChatService {
    
    /**
     * 创建会话
     */
    ChatSession createSession(List<Long> knowledgeBaseIds, String title);
    
    /**
     * 获取会话列表
     */
    List<RagChatSessionListItem> listSessions();
    
    /**
     * 获取会话详情
     */
    RagChatSessionDetail getSessionDetail(Long sessionId);
    
    /**
     * 更新会话标题
     */
    void updateSessionTitle(Long sessionId, String title);
    
    /**
     * 更新会话知识库
     */
    void updateKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds);
    
    /**
     * 切换置顶状态
     */
    void togglePin(Long sessionId);
    
    /**
     * 删除会话
     */
    void deleteSession(Long sessionId);
    
    /**
     * 发送消息（流式）
     */
    SseEmitter sendMessageStream(Long sessionId, String question);
}

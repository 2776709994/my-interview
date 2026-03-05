package com.edu.muc.app.modules.ragchat.dto;

import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.ragchat.domain.ChatMessage;
import lombok.Data;

import java.util.List;

@Data
public class RagChatSessionDetail {
    private Long id;
    private String title;
    private List<KnowledgeDocument> knowledgeBases;
    private List<ChatMessage> messages;
    private String createdAt;
    private String updatedAt;
}

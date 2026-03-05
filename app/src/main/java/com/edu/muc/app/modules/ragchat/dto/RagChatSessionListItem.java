package com.edu.muc.app.modules.ragchat.dto;

import lombok.Data;

import java.util.List;

@Data
public class RagChatSessionListItem {
    private Long id;
    private String title;
    private Integer messageCount;
    private List<String> knowledgeBaseNames;
    private String updatedAt;
    private Boolean isPinned;
}

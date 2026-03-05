package com.edu.muc.app.modules.ragchat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_sessions")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String knowledgeBaseIds; // 存储选中的知识库ID列表，如 "[1,2]"

    private Boolean isPinned = false;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

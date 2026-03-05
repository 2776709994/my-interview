package com.edu.muc.app.modules.ragchat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_messages")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String type; // 'user' or 'assistant'

    private String content;

    private LocalDateTime createdAt;
}

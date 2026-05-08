package com.edu.muc.app.modules.llmprovider.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_global_setting")
public class LlmGlobalSettingEntity {

    public static final Long SINGLETON_ID = 1L;

    @TableId("id")
    private Long id;

    @TableField("default_chat_provider_id")
    private String defaultChatProviderId;

    @TableField("default_embedding_provider_id")
    private String defaultEmbeddingProviderId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

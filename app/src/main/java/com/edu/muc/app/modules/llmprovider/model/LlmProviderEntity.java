package com.edu.muc.app.modules.llmprovider.model;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("llm_provider_config")
public class LlmProviderEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key_ciphertext")
    private String apiKeyCiphertext;

    @TableField("api_key_nonce")
    private String apiKeyNonce;

    private String model;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_dimensions")
    private Integer embeddingDimensions;

    @TableField("supports_embedding")
    private boolean supportsEmbedding;

    private Double temperature;

    private boolean enabled;

    private boolean builtin;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

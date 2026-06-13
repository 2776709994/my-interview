package com.edu.muc.app.modules.knowledgebase.service.impl;

import com.edu.muc.app.common.JsonUtils;
import com.edu.muc.app.modules.knowledgebase.config.RagRetrievalConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 高频问题向量缓存（"向量化前置"）。
 * <p>
 * 思路：问题原文 → MD5 → Redis key（{@code embed:xxx}），value 为 1024 维 JSON 数组。
 * 命中直接返回向量，跳过 embedding 模型调用（省一次 30~100ms 网络请求与 token 费用），
 * 对重复/近似问题效果显著；未命中才调模型并回填缓存（TTL 兜底）。
 * </p>
 * <p>
 * 可靠性：Redis 不可用 / 反序列化失败时 fail-open 降级为直接调用模型，绝不影响主流程。
 * 仅缓存"问题向量化"；文档分块向量化（上传/重向量化，一次性操作）不经过本缓存。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingCacheService {

    private static final String KEY_PREFIX = "embed:";

    private final StringRedisTemplate redisTemplate;
    private final RagRetrievalConfig retrievalConfig;
    private final ObjectMapper objectMapper;

    /**
     * 优先取缓存，未命中则调用模型并回填缓存。
     *
     * @param model embedding 模型（由调用方传入，兼容 LlmProviderRegistry 动态取模型）
     * @param text  问题原文
     * @return 向量数组
     */
    public float[] embedCached(EmbeddingModel model, String text) {
        if (!retrievalConfig.getEmbeddingCache().isEnabled()
                || text == null || text.isBlank()) {
            return model.embed(text);
        }
        String key = KEY_PREFIX + md5(text);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                float[] vector = objectMapper.readValue(cached, float[].class);
                log.info("⚡ Embedding 缓存命中: {}", key);
                return vector;
            }
        } catch (Exception e) {
            log.warn("⚠️ Embedding 缓存读取失败，直接调用模型: {}", e.getMessage());
        }

        float[] vector = model.embed(text);
        try {
            redisTemplate.opsForValue().set(key, JsonUtils.convertEmbeddingToJson(vector),
                    Duration.ofSeconds(retrievalConfig.getEmbeddingCache().getTtlSeconds()));
        } catch (Exception e) {
            log.warn("⚠️ Embedding 缓存写入失败（不影响主流程）: {}", e.getMessage());
        }
        return vector;
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论不可达：退化为哈希码
            return Integer.toHexString(text.hashCode());
        }
    }
}

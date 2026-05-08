package com.edu.muc.app.modules.llmprovider.service;

import com.edu.muc.app.common.config.LlmProviderProperties;
import com.edu.muc.app.common.config.LlmProviderProperties.ProviderConfig;
import com.edu.muc.app.modules.llmprovider.mapper.LlmGlobalSettingMapper;
import com.edu.muc.app.modules.llmprovider.mapper.LlmProviderMapper;
import com.edu.muc.app.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.edu.muc.app.modules.llmprovider.model.LlmProviderEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

    private final LlmProviderProperties properties;
    private final LlmProviderMapper providerMapper;
    private final LlmGlobalSettingMapper globalSettingMapper;
    private final ApiKeyEncryptionService encryptionService;

    @PostConstruct
    @Transactional
    public void seedProvidersIfNecessary() {
        try {
            if (providerMapper.selectCount(null) == 0) {
                seedProviders();
            }
            ensureGlobalSetting();
        } catch (Exception e) {
            log.warn("LLM Provider 种子数据初始化失败（数据库可能未就绪）: {}", e.getMessage());
            log.info("请在数据库就绪后通过管理界面手动配置 LLM Provider");
        }
    }

    private void seedProviders() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.warn("No app.ai.providers seed configuration found");
            return;
        }

        providers.forEach((id, config) -> {
            if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
                log.warn("Skip invalid provider seed: id={}", id);
                return;
            }
            ApiKeyEncryptionService.EncryptedValue encrypted =
                encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
            boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
                || !isBlank(config.getEmbeddingModel());

            LlmProviderEntity entity = LlmProviderEntity.builder()
                .id(id)
                .baseUrl(config.getBaseUrl())
                .apiKeyNonce(encrypted.nonce())
                .apiKeyCiphertext(encrypted.ciphertext())
                .model(config.getModel())
                .embeddingModel(trimOrNull(config.getEmbeddingModel()))
                .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
                .supportsEmbedding(supportsEmbedding)
                .temperature(config.getTemperature())
                .enabled(true)
                .builtin(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
            providerMapper.insert(entity);
        });
        log.info("Seeded {} LLM providers from application configuration", providerMapper.selectCount(null));
    }

    private void ensureGlobalSetting() {
        if (globalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID) != null) {
            return;
        }
        String defaultChatProvider = resolveExistingProvider(
            properties.getDefaultProvider(),
            providerMapper.selectList(null).stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
        );
        String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
            ? properties.getDefaultEmbeddingProvider()
            : defaultChatProvider;
        String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

        LlmGlobalSettingEntity setting = LlmGlobalSettingEntity.builder()
            .id(LlmGlobalSettingEntity.SINGLETON_ID)
            .defaultChatProviderId(defaultChatProvider)
            .defaultEmbeddingProviderId(defaultEmbeddingProvider)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        globalSettingMapper.insert(setting);
        log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
            defaultChatProvider, defaultEmbeddingProvider);
    }

    private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
        if (!isBlank(preferredProvider) && providerMapper.selectById(preferredProvider) != null) {
            return preferredProvider;
        }
        return fallbackProvider;
    }

    private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
        LlmProviderEntity preferred = providerMapper.selectById(preferredProvider);
        if (preferred != null && canProvideEmbedding(preferred)) {
            return preferred.getId();
        }
        return providerMapper.selectList(null).stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider);
    }

    private boolean canProvideEmbedding(LlmProviderEntity provider) {
        return provider.isEnabled()
            && provider.isSupportsEmbedding()
            && !isBlank(provider.getEmbeddingModel());
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.edu.muc.app.common.ai;

import com.edu.muc.app.common.config.LlmProviderProperties;
import com.edu.muc.app.common.config.LlmProviderProperties.AdvisorConfig;
import com.edu.muc.app.common.config.LlmProviderProperties.ProviderConfig;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.common.exception.ErrorCode;
import com.edu.muc.app.modules.llmprovider.mapper.LlmGlobalSettingMapper;
import com.edu.muc.app.modules.llmprovider.mapper.LlmProviderMapper;
import com.edu.muc.app.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.edu.muc.app.modules.llmprovider.model.LlmProviderEntity;
import com.edu.muc.app.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderMapper providerMapper;
    private final LlmGlobalSettingMapper globalSettingMapper;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;

    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    @Autowired
    public LlmProviderRegistry(
        LlmProviderProperties properties,
        @Autowired(required = false) LlmProviderMapper providerMapper,
        @Autowired(required = false) LlmGlobalSettingMapper globalSettingMapper,
        @Autowired(required = false) ApiKeyEncryptionService encryptionService,
        @Autowired(required = false) ToolCallingManager toolCallingManager,
        @Autowired(required = false) ObservationRegistry observationRegistry,
        @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerMapper = providerMapper;
        this.globalSettingMapper = globalSettingMapper;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveDefaultChatProviderId());
    }

    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatClient(providerId);
        }
        return getDefaultChatClient();
    }

    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    public void reload() {
        int size = clientCache.size() + embeddingModelCache.size();
        clientCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(advisor));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    private ChatClient createVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = buildChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true, true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    private OpenAiChatModel buildChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.model());

        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(config.model())
            .temperature(config.temperature() != null ? config.temperature() : 0.2)
            .build();

        return new OpenAiChatModel(
            openAiApi,
            options,
            toolCallingManager,
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return new OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            options,
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(
                    config.isToolCallConversationHistoryEnabled(),
                    config.isStreamToolCallResponses()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    private ToolCallAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled,
                                                  boolean streamToolCallResponses) {
        return ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .streamToolCallResponses(streamToolCallResponses)
            .build();
    }

    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
            ? providerId : resolveDefaultChatProviderId();
    }

    private String resolveDefaultChatProviderId() {
        if (globalSettingMapper == null) {
            return properties.getDefaultProvider();
        }
        LlmGlobalSettingEntity setting = globalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID);
        if (setting != null && !isBlank(setting.getDefaultChatProviderId())) {
            return setting.getDefaultChatProviderId();
        }
        return properties.getDefaultProvider();
    }

    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingMapper == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();
        }
        LlmGlobalSettingEntity setting = globalSettingMapper.selectById(LlmGlobalSettingEntity.SINGLETON_ID);
        if (setting != null && !isBlank(setting.getDefaultEmbeddingProviderId())) {
            return setting.getDefaultEmbeddingProviderId();
        }
        return !isBlank(properties.getDefaultEmbeddingProvider())
            ? properties.getDefaultEmbeddingProvider()
            : properties.getDefaultProvider();
    }

    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (providerMapper == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerMapper.selectById(providerId);
        if (entity == null || !entity.isEnabled()) {
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature
    ) {
    }
}

package com.edu.muc.app.modules.knowledgebase.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope gte-rerank-v2 重排客户端（交叉编码器精排）。
 * <p>
 * 与 chat/embedding 共用同一个 {@code DASHSCOPE_API_KEY}（spring.ai.openai.api-key），
 * 走 DashScope 独立的 rerank REST 端点。任何失败返回 {@code null}，由调用方
 * fail-open 降级为 RRF 融合顺序，保证精排不可用时系统不中断。
 * </p>
 */
@Slf4j
@Component
public class DashScopeRerankClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DashScopeRerankClient(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${rag.retrieval.rerank.timeout-seconds:5}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 对候选文档重排，返回按相关性降序排列的原始下标列表。
     *
     * @param url       DashScope rerank 端点
     * @param model     重排模型（如 gte-rerank-v2）
     * @param query     用户问题原文
     * @param documents 候选文档内容列表（与待排序集合一一对应）
     * @param topN      返回前 N 个相关文档
     * @return 降序的下标列表；任何异常返回 {@code null}（调用方降级为 RRF 顺序）
     */
    public List<Integer> rerank(String url, String model, String query,
                                List<String> documents, int topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("query", query);
            input.put("documents", documents);
            input.put("top_n", topN);
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", input);

            String resp = restClient.post()
                    .uri(URI.create(url))
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(resp);
            JsonNode results = root.path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("⚠️ Rerank 返回空结果，降级为 RRF 顺序");
                return null;
            }
            List<Integer> ordered = new ArrayList<>();
            for (JsonNode node : results) {
                int idx = node.path("index").asInt(-1);
                if (idx >= 0) {
                    ordered.add(idx);
                }
            }
            if (ordered.isEmpty()) {
                return null;
            }
            log.info("🎯 Rerank 完成: 输入 {} 条 → 输出 {} 条 (model={})", documents.size(), ordered.size(), model);
            return ordered;
        } catch (RestClientResponseException e) {
            log.warn("⚠️ Rerank 调用失败(HTTP {}): {}，降级为 RRF 顺序", e.getStatusCode().value(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("⚠️ Rerank 调用异常: {}，降级为 RRF 顺序", e.getMessage());
            return null;
        }
    }
}

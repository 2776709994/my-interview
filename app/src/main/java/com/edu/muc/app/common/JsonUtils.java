package com.edu.muc.app.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON 工具类
 */
public class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {}

    /**
     * 从包含 Markdown 的 AI 响应中提取 JSON
     */
    public static String extractJson(String response) {
        if (response == null) return "{}";

        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```", "").trim();
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;
    }

    /**
     * 将向量数组转换为 JSON 字符串
     */
    public static String convertEmbeddingToJson(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 安全获取 int 值
     */
    public static int safeGetInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) {
            try { return Integer.parseInt((String) obj); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * 安全获取 double 值
     */
    public static double safeGetDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        if (obj instanceof String) {
            try { return Double.parseDouble((String) obj); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    /**
     * 解析 AI 评估结果
     */
    public static Map<String, Object> parseEvaluationResult(String aiResponse) throws Exception {
        String jsonStr = extractJson(aiResponse);
        return MAPPER.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 获取共享的 ObjectMapper
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}

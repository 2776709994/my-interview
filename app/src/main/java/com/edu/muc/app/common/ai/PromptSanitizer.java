package com.edu.muc.app.common.ai;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Prompt 注入净化工具。
 * <p>
 * 用于直接拼接用户文本的场景：清洗危险模式，并用不可预测的分隔符包裹用户数据。
 * </p>
 */
@Component
public class PromptSanitizer {

    // 行首角色标记：只匹配行首，避免误杀普通文本
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
        "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    // 注入短语：精确匹配
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
        "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
        "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
        "|(new\\s+instructions?:)" +
        "|忽略之前的指令" +
        "|忘记之前的指令" +
        "|忽略以上所有" +
        "|你不再是" +
        "|你的新角色是",
        Pattern.CASE_INSENSITIVE
    );

    // 分隔符伪造：匹配项目中 .st 模板使用的静态分隔符
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
        "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    // XML 边界标签伪造
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
        "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;

        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            result = roleMatcher.replaceAll("[filtered-role-marker]");
        }
        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            result = phraseMatcher.replaceAll("[filtered]");
        }
        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }
        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        return result;
    }

    /**
     * 用不可预测的分隔符包裹用户文本，防止伪造分隔符注入。
     */
    public String wrapWithDelimiters(String label, String text) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        return openTag + "\n" + text + "\n" + closeTag;
    }
}

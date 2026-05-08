package com.edu.muc.app.modules.interviewschedule.service;

import com.edu.muc.app.modules.interviewschedule.model.CreateInterviewRequest;
import com.edu.muc.app.modules.interviewschedule.model.ParseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试邀约解析服务 - 规则解析
 * AI 解析部分待 llmprovider 模块就绪后补充
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewParseService {

    // Date formatters
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER_2 = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
        "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
        "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
    );

    // Feishu patterns
    private static final Pattern TIME_PATTERN_FEISHU = Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");
    private static final Pattern LINK_PATTERN_FEISHU = Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");
    private static final Pattern COMPANY_PATTERN_FEISHU = Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_FEISHU = Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern ROUND_PATTERN_FEISHU = Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]");

    // Tencent meeting patterns
    private static final Pattern TIME_PATTERN_TENCENT = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");
    private static final Pattern MEETING_ID_PATTERN_TENCENT = Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");
    private static final Pattern PASSWORD_PATTERN_TENCENT = Pattern.compile("密码[：:]?\\s*(\\d{4,})");
    private static final Pattern COMPANY_PATTERN_TENCENT = Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_TENCENT = Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");

    // Zoom patterns
    private static final Pattern LINK_PATTERN_ZOOM = Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");
    private static final Pattern DATE_PATTERN_ZOOM = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");
    private static final Pattern HOUR_PATTERN_ZOOM = Pattern.compile("(\\d{1,2}:\\d{2})");

    // Round number pattern
    private static final Pattern ROUND_NUMBER_PATTERN = Pattern.compile("[一二三四五六七八九十]|\\d");

    /**
     * Parse interview schedule text using rule-based parsing
     */
    public ParseResponse parse(String rawText, String source) {
        log.info("开始解析文本，来源: {}, 文本长度: {}", source, rawText != null ? rawText.length() : 0);

        if (rawText == null || rawText.trim().isEmpty()) {
            log.warn("Input text is null or empty");
            return new ParseResponse(false, null, 0.0, "none", "输入文本为空");
        }

        // Try rule-based parsing
        CreateInterviewRequest result = tryRuleParsing(rawText, source);
        if (isValidResult(result)) {
            log.info("规则解析成功");
            return new ParseResponse(true, result, 0.95, "rule", "规则解析成功");
        }

        // Rule parsing failed - AI parsing deferred until llmprovider module is ready
        log.warn("规则解析失败，AI 解析待 llmprovider 模块就绪后可用");
        return new ParseResponse(false, null, 0.0, "none", "解析失败");
    }

    private CreateInterviewRequest tryRuleParsing(String rawText, String source) {
        if ("feishu".equalsIgnoreCase(source)) {
            return parseFeishu(rawText);
        } else if ("tencent".equalsIgnoreCase(source)) {
            return parseTencent(rawText);
        } else if ("zoom".equalsIgnoreCase(source)) {
            return parseZoom(rawText);
        }

        if (rawText.contains("飞书") || rawText.contains("Feishu") || rawText.contains("meeting.feishu.cn")) {
            CreateInterviewRequest result = parseFeishu(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting") || rawText.contains("会议号")) {
            CreateInterviewRequest result = parseTencent(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
            CreateInterviewRequest result = parseZoom(rawText);
            if (isValidResult(result)) return result;
        }

        CreateInterviewRequest result = parseFeishu(rawText);
        if (isValidResult(result)) return result;

        result = parseTencent(rawText);
        if (isValidResult(result)) return result;

        return parseZoom(rawText);
    }

    // ========== Feishu Parsing ==========

    private CreateInterviewRequest parseFeishu(String rawText) {
        log.debug("尝试解析飞书格式");
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher timeMatcher = TIME_PATTERN_FEISHU.matcher(rawText);
            if (timeMatcher.find()) {
                request.setInterviewTime(parseDateTime(timeMatcher.group(1)));
            }

            Matcher linkMatcher = LINK_PATTERN_FEISHU.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            Matcher companyMatcher = COMPANY_PATTERN_FEISHU.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            Matcher positionMatcher = POSITION_PATTERN_FEISHU.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            Matcher roundMatcher = ROUND_PATTERN_FEISHU.matcher(rawText);
            if (roundMatcher.find()) {
                request.setRoundNumber(parseRoundNumber(roundMatcher.group()));
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.error("飞书格式解析异常", e);
            return request;
        }
    }

    // ========== Tencent Meeting Parsing ==========

    private CreateInterviewRequest parseTencent(String rawText) {
        log.debug("尝试解析腾讯会议格式");
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher timeMatcher = TIME_PATTERN_TENCENT.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1) + " " + timeMatcher.group(2);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            Matcher meetingIdMatcher = MEETING_ID_PATTERN_TENCENT.matcher(rawText);
            Matcher passwordMatcher = PASSWORD_PATTERN_TENCENT.matcher(rawText);
            StringBuilder meetingLink = new StringBuilder();
            if (meetingIdMatcher.find()) {
                meetingLink.append("会议号: ").append(meetingIdMatcher.group());
            }
            if (passwordMatcher.find()) {
                meetingLink.append(" 密码: ").append(passwordMatcher.group());
            }
            if (meetingLink.length() > 0) {
                request.setMeetingLink(meetingLink.toString());
            }

            Matcher companyMatcher = COMPANY_PATTERN_TENCENT.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            Matcher positionMatcher = POSITION_PATTERN_TENCENT.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.error("腾讯会议格式解析异常", e);
            return request;
        }
    }

    // ========== Zoom Parsing ==========

    private CreateInterviewRequest parseZoom(String rawText) {
        log.debug("尝试解析 Zoom 格式");
        CreateInterviewRequest request = new CreateInterviewRequest();
        try {
            Matcher linkMatcher = LINK_PATTERN_ZOOM.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            Matcher dateMatcher = DATE_PATTERN_ZOOM.matcher(rawText);
            Matcher hourMatcher = HOUR_PATTERN_ZOOM.matcher(rawText);
            if (dateMatcher.find() && hourMatcher.find()) {
                String timeStr = dateMatcher.group(1) + " " + hourMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            request.setInterviewType("VIDEO");
            return request;
        } catch (Exception e) {
            log.error("Zoom 格式解析异常", e);
            return request;
        }
    }

    // ========== Helper Methods ==========

    private LocalDateTime parseDateTime(String timeStr) {
        try {
            timeStr = timeStr.replace("/", "-");
            if (timeStr.length() == 16) {
                return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
            } else if (timeStr.length() == 19) {
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return LocalDateTime.parse(timeStr);
        } catch (Exception e) {
            log.error("时间解析失败: {}", timeStr, e);
            return null;
        }
    }

    private int parseRoundNumber(String text) {
        if (text == null) return 1;
        text = text.trim();
        if (text.matches("\\d+")) {
            return Integer.parseInt(text);
        }
        Matcher matcher = ROUND_NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            String num = matcher.group();
            return CHINESE_NUMBERS.getOrDefault(num, Integer.parseInt(num.replaceAll("\\D", "")));
        }
        return 1;
    }

    private boolean isValidResult(CreateInterviewRequest result) {
        return result != null
                && result.getCompanyName() != null
                && result.getPosition() != null
                && result.getInterviewTime() != null;
    }
}

package com.edu.muc.app.infrastructure.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文本清理服务单元测试（镜像 interview-guide 源项目同名测试用例）
 */
class TextCleaningServiceTest {

    private final TextCleaningService cleaningService = new TextCleaningService();

    @Test
    void cleanText_shouldRemoveImageFilenameLines() {
        String input = "张三\nimage123.png\nJava 开发工程师";
        String cleaned = cleaningService.cleanText(input);
        assertEquals(false, cleaned.contains("image123.png"));
        assertEquals(true, cleaned.contains("张三"));
        assertEquals(true, cleaned.contains("Java 开发工程师"));
    }

    @Test
    void cleanText_shouldPreserveImageFilenameInsideSentence() {
        String input = "请查看 image001.png 文件";
        assertEquals(true, cleaningService.cleanText(input).contains("image001.png"));
    }

    @Test
    void cleanText_shouldRemoveImageUrls() {
        String input = "技能: Java\nhttps://cdn.example.com/avatar.jpg?x=1\n经验: 3年";
        String cleaned = cleaningService.cleanText(input);
        assertEquals(false, cleaned.contains("https://cdn.example.com"));
    }

    @Test
    void cleanText_shouldRemoveFileUrls() {
        String input = "工作经历\nfile:///tmp/tika-tmp/scan.png\n2020-2023";
        String cleaned = cleaningService.cleanText(input);
        assertEquals(false, cleaned.contains("file:///tmp"));
    }

    @Test
    void cleanText_shouldRemoveSeparatorLines() {
        String input = "个人信息\n----------\n教育经历";
        String cleaned = cleaningService.cleanText(input);
        assertEquals(false, cleaned.contains("---"));
        assertEquals(true, cleaned.contains("个人信息"));
        assertEquals(true, cleaned.contains("教育经历"));
    }

    @Test
    void cleanText_shouldRemoveControlCharsButKeepNewlineAndTab() {
        String input = "姓名:\u0000 张三\t年龄: 25\n职位: 工程师\u001F";
        String cleaned = cleaningService.cleanText(input);
        assertEquals(false, cleaned.contains("\u0000"));
        assertEquals(false, cleaned.contains("\u001F"));
        assertEquals(true, cleaned.contains("\t"));
    }

    @Test
    void cleanText_shouldCompressBlankLines() {
        String input = "第一段\n\n\n\n\n第二段";
        assertEquals("第一段\n\n第二段", cleaningService.cleanText(input));
    }

    @Test
    void cleanText_shouldReturnEmptyForBlankInput() {
        assertEquals("", cleaningService.cleanText(null));
        assertEquals("", cleaningService.cleanText("   \n  "));
    }

    @Test
    void cleanTextWithLimit_shouldTruncate() {
        String input = "a".repeat(100);
        assertEquals(50, cleaningService.cleanTextWithLimit(input, 50).length());
    }

    @Test
    void cleanToSingleLine_shouldCollapseNewlines() {
        assertEquals("a b c", cleaningService.cleanToSingleLine("a\nb\r\nc"));
    }

    @Test
    void stripHtml_shouldRemoveTagsAndDecodeEntities() {
        String input = "<p>张三&nbsp;&amp;李四</p>";
        assertEquals("张三 &李四", cleaningService.stripHtml(input));
    }
}

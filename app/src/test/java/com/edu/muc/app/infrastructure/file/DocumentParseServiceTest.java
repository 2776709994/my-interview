package com.edu.muc.app.infrastructure.file;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析服务单元测试（纯 Tika 内存解析，不依赖外部设施）
 */
class DocumentParseServiceTest {

    private final DocumentParseService parseService =
            new DocumentParseService(new TextCleaningService());

    @Test
    void parseContent_plainText_shouldExtractAndClean() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.txt", "text/plain",
                "张三\n\n\n\nJava 开发工程师".getBytes(StandardCharsets.UTF_8));

        String content = parseService.parseContent(file);

        assertTrue(content.contains("张三"));
        assertTrue(content.contains("Java 开发工程师"));
        // 连续空行应被压缩
        assertEquals(false, content.contains("\n\n\n"));
    }

    @Test
    void parseContent_emptyFile_shouldReturnEmptyString() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        assertEquals("", parseService.parseContent(file));
    }

    @Test
    void parseContent_bytesOverload_shouldMatchMultipartFileResult() {
        byte[] data = "技能: Java, Spring Boot".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.txt", "text/plain", data);

        assertEquals(
                parseService.parseContent(file),
                parseService.parseContent(data, "resume.txt"));
    }
}

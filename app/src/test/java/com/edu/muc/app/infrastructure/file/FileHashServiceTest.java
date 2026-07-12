package com.edu.muc.app.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 文件哈希服务单元测试：SHA-256 算法正确性与流式/字节一致性
 */
class FileHashServiceTest {

    private final FileHashService fileHashService = new FileHashService();

    @Test
    void calculateHash_shouldMatchKnownSha256Vector() {
        // "abc" 的标准 SHA-256 测试向量
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                fileHashService.calculateHash(data));
    }

    @Test
    void calculateHash_streamAndBytes_shouldProduceSameDigest() {
        byte[] data = "简历内容-测试数据".getBytes(StandardCharsets.UTF_8);
        String byBytes = fileHashService.calculateHash(data);
        String byStream = fileHashService.calculateHash(new ByteArrayInputStream(data));
        assertEquals(byBytes, byStream);
    }

    @Test
    void calculateHash_differentContent_shouldProduceDifferentDigest() {
        byte[] a = "resume-A".getBytes(StandardCharsets.UTF_8);
        byte[] b = "resume-B".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(fileHashService.calculateHash(a), fileHashService.calculateHash(b));
    }

    @Test
    void calculateHash_sameContent_shouldDeduplicate() {
        byte[] a = "完全相同的简历内容".getBytes(StandardCharsets.UTF_8);
        byte[] b = "完全相同的简历内容".getBytes(StandardCharsets.UTF_8);
        assertEquals(fileHashService.calculateHash(a), fileHashService.calculateHash(b));
    }
}

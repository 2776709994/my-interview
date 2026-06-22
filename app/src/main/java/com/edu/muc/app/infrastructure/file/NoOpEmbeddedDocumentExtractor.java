package com.edu.muc.app.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.xml.sax.ContentHandler;

import java.io.InputStream;

/**
 * 空操作的嵌入文档提取器
 * 用于禁用 Tika 对嵌入资源（图片、附件等）的解析，
 * 避免提取图片引用与临时文件路径污染正文文本。
 */
@Slf4j
public class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

    /**
     * 是否应该解析嵌入文档
     *
     * @param metadata 文档元数据
     * @return 始终返回 false，禁用嵌入文档解析
     */
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        // 记录跳过的嵌入文档（使用字符串常量，兼容不同 Tika 版本）
        String resourceName = metadata.get("resourceName");
        if (resourceName != null) {
            log.debug("Skip embedded document: {}", resourceName);
        }
        return false;
    }

    /**
     * 解析嵌入文档（空实现）
     * 由于 shouldParseEmbedded 返回 false，此方法不会被调用
     */
    @Override
    public void parseEmbedded(InputStream stream, ContentHandler handler,
                              Metadata metadata, boolean outputHtml) {
        // 空实现
    }
}

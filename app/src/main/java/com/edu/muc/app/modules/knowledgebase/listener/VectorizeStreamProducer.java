package com.edu.muc.app.modules.knowledgebase.listener;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.async.AbstractStreamProducer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识文档向量化任务生产者（Redis Stream）
 * <p>
 * 上传/重新向量化时入队即返回，分块与 Embedding 等耗时操作由消费者异步完成，
 * 前端通过文档的 vectorStatus（PENDING→PROCESSING→COMPLETED/FAILED）轮询进度。
 * </p>
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<Long> {

    private final KnowledgeDocumentMapper documentMapper;

    public VectorizeStreamProducer(RedisTemplate<String, Object> redisTemplate,
                                   KnowledgeDocumentMapper documentMapper) {
        super(redisTemplate);
        this.documentMapper = documentMapper;
    }

    /**
     * 发送文档向量化任务
     *
     * @return 是否入队成功
     */
    public boolean send(Long kbId) {
        return sendTask(kbId);
    }

    @Override
    protected String taskDisplayName() {
        return "文档向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Long kbId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_KB_ID, String.valueOf(kbId));
    }

    @Override
    protected String payloadIdentifier(Long kbId) {
        return "kbId=" + kbId;
    }

    @Override
    protected void onSendFailed(Long kbId, String error) {
        try {
            documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, kbId)
                    .set(KnowledgeDocument::getVectorStatus, "FAILED")
                    .set(KnowledgeDocument::getVectorError, error));
            log.warn("文档向量化任务入队失败，已标记为 FAILED: kbId={}", kbId);
        } catch (Exception e) {
            log.error("标记文档向量化失败状态时出错: kbId={}", kbId, e);
        }
    }
}

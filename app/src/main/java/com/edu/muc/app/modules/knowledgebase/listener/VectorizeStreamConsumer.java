package com.edu.muc.app.modules.knowledgebase.listener;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.knowledgebase.domain.KnowledgeDocument;
import com.edu.muc.app.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import com.edu.muc.app.modules.knowledgebase.service.KnowledgeDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 知识文档向量化异步消费者（Redis Stream 模板方法实现）
 * <p>
 * 监听 Redis Stream: knowledgebase:vectorize:stream。
 * 状态机：PENDING →（领取，带状态守卫）→ PROCESSING → COMPLETED/FAILED（超过 3 次重试）。
 * 业务流程：读取父文档内容 → 分块 → Embedding → 子文档（含向量）入库。
 * </p>
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<Long> {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentService documentService;
    private final ExecutorService ragQueryExecutor;

    public VectorizeStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                   StreamPendingRecoverer pendingRecoverer,
                                   KnowledgeDocumentMapper documentMapper,
                                   KnowledgeDocumentService documentService,
                                   @Qualifier("ragQueryExecutor") ExecutorService ragQueryExecutor) {
        super(redisTemplate, pendingRecoverer);
        this.documentMapper = documentMapper;
        this.documentService = documentService;
        this.ragQueryExecutor = ragQueryExecutor;
    }

    @Override
    protected ExecutorService executor() {
        return ragQueryExecutor;
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
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "knowledgebase-vectorize-listener";
    }

    @Override
    protected Long parsePayload(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(AsyncTaskStreamConstants.FIELD_KB_ID);
        return raw == null ? null : Long.parseLong(raw.toString().trim());
    }

    @Override
    protected String payloadIdentifier(Long kbId) {
        return "kbId=" + kbId;
    }

    @Override
    protected boolean shouldSkip(Long kbId) {
        KnowledgeDocument doc = documentMapper.selectById(kbId);
        return doc != null && "COMPLETED".equals(doc.getVectorStatus());
    }

    @Override
    protected boolean tryMarkProcessing(Long kbId) {
        // 状态守卫：仅 PENDING/PROCESSING（重试/宕机重投）允许领取
        return documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, kbId)
                .in(KnowledgeDocument::getVectorStatus, "PENDING", "PROCESSING")
                .set(KnowledgeDocument::getVectorStatus, "PROCESSING")) > 0;
    }

    @Override
    protected void processBusiness(Long kbId) throws Exception {
        // 分块 → Embedding → 子文档入库（耗时操作，失败抛异常走自动重试）
        documentService.vectorizeDocument(kbId);
    }

    @Override
    protected void markCompleted(Long kbId) {
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, kbId)
                .set(KnowledgeDocument::getVectorStatus, "COMPLETED")
                .set(KnowledgeDocument::getVectorError, null)
                .set(KnowledgeDocument::getProcessedAt, LocalDateTime.now()));
        log.info("🎉 文档向量化完成: kbId={}", kbId);
    }

    @Override
    protected void markFailed(Long kbId, String error) {
        log.error("❌ 文档向量化最终失败: kbId={}, 原因: {}", kbId, error);
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, kbId)
                .set(KnowledgeDocument::getVectorStatus, "FAILED")
                .set(KnowledgeDocument::getVectorError, error));
    }
}

package com.edu.muc.app.modules.resume.listener;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.muc.app.common.async.AbstractStreamProducer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 简历分析任务生产者（Redis Stream）
 * <p>
 * 接口入队即返回，分析进度由前端轮询简历的 analyzeStatus（PENDING→PROCESSING→COMPLETED/FAILED）。
 * 入队失败时兜底将简历标记为 FAILED，避免任务静默丢失导致前端无限轮询。
 * </p>
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<Long> {

    private final ResumesMapper resumesMapper;

    public AnalyzeStreamProducer(RedisTemplate<String, Object> redisTemplate,
                                 ResumesMapper resumesMapper) {
        super(redisTemplate);
        this.resumesMapper = resumesMapper;
    }

    /**
     * 发送简历分析任务
     *
     * @return 是否入队成功
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean send(Long resumeId) {
        return sendTask(resumeId);
    }

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Long resumeId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_RESUME_ID, String.valueOf(resumeId));
    }

    @Override
    protected String payloadIdentifier(Long resumeId) {
        return "resumeId=" + resumeId;
    }

    @Override
    protected void onSendFailed(Long resumeId, String error) {
        try {
            resumesMapper.update(null, new LambdaUpdateWrapper<Resumes>()
                    .eq(Resumes::getId, resumeId)
                    .eq(Resumes::getAnalyzeStatus, AsyncTaskStatus.PENDING.name())
                    .set(Resumes::getAnalyzeStatus, AsyncTaskStatus.FAILED.name())
                    .set(Resumes::getAnalyzeError, error));
        } catch (Exception e) {
            log.error("标记简历分析失败状态时出错: resumeId={}", resumeId, e);
        }
    }
}

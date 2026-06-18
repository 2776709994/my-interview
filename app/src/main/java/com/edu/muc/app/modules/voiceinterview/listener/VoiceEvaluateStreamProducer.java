package com.edu.muc.app.modules.voiceinterview.listener;

import com.edu.muc.app.common.async.AbstractStreamProducer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者（Redis Stream）
 * <p>
 * 注意：本组件不依赖任何 Service（避免与 VoiceInterviewService 形成构造器循环依赖），
 * 入队失败时仅记录日志并返回 false，由调用方负责更新评估状态。
 * </p>
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer extends AbstractStreamProducer<String> {

    public VoiceEvaluateStreamProducer(RedisTemplate<String, Object> redisTemplate) {
        super(redisTemplate);
    }

    /**
     * 发送语音面试评估任务
     *
     * @param sessionId 语音面试会话ID
     * @return 是否入队成功（失败由调用方更新评估状态，避免前端一直等待）
     */
    public boolean sendEvaluateTask(String sessionId) {
        return sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId);
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        // 不依赖 Service（避免循环依赖），状态兜底由调用方完成
        log.warn("语音面试评估任务入队失败，由调用方兜底处理: sessionId={}, error={}", sessionId, error);
    }
}

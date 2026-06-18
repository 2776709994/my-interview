package com.edu.muc.app.modules.interview.listener;

import com.edu.muc.app.common.async.AbstractStreamProducer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者（Redis Stream）
 * <p>
 * 交卷/手动触发评估时入队即返回，前端轮询会话的 evaluateStatus 获取进度。
 * 入队失败仅记录日志，由调用方根据返回值决定状态兜底（交卷路径不阻塞、触发路径抛业务异常）。
 * </p>
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {

    public EvaluateStreamProducer(RedisTemplate<String, Object> redisTemplate) {
        super(redisTemplate);
    }

    /**
     * 发送面试评估任务
     *
     * @return 是否入队成功
     */
    public boolean send(String sessionId) {
        return sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId);
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        // 状态兜底由调用方负责：交卷路径仅记录日志，触发评估路径抛出业务异常提示用户重试
        log.warn("面试评估任务入队失败，由调用方兜底处理: sessionId={}, error={}", sessionId, error);
    }
}

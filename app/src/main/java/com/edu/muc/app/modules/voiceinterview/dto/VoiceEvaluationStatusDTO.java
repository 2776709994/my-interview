package com.edu.muc.app.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 语音面试评估状态响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

    /**
     * 评估任务状态: PENDING / PROCESSING / COMPLETED / FAILED
     */
    private String evaluateStatus;

    /**
     * FAILED 时的错误信息
     */
    private String evaluateError;

    /**
     * 完整评估结果，仅当 status 为 COMPLETED 时存在
     */
    private VoiceEvaluationDetailDTO evaluation;
}

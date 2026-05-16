package com.edu.muc.app.modules.voiceinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试评估实体
 * <p>
 * 存储与文字面试对齐的评估结果：逐题评估、总体反馈、优点、改进建议和参考答案。
 * 所有结构化数据（数组/对象）以 JSON TEXT 列存储。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("voice_interview_evaluations")
public class VoiceInterviewEvaluationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Integer overallScore;

    private String overallFeedback;

    private String questionEvaluationsJson;

    private String strengthsJson;

    private String improvementsJson;

    private String referenceAnswersJson;

    private String interviewerRole;

    private LocalDateTime interviewDate;

    private LocalDateTime createdAt;
}

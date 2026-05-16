package com.edu.muc.app.modules.voiceinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("voice_interview_sessions")
public class VoiceInterviewSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String roleType;

    @Default
    private String skillId = "java-backend";

    @Default
    private String difficulty = "mid";

    private String customJdText;

    private Long resumeId;

    @Default
    private Boolean introEnabled = true;

    @Default
    private Boolean techEnabled = true;

    @Default
    private Boolean projectEnabled = true;

    @Default
    private Boolean hrEnabled = true;

    @Default
    private String llmProvider = "dashscope";

    private InterviewPhase currentPhase;

    @Default
    private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS;

    @Default
    private Integer plannedDuration = 30;

    private Integer actualDuration;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime pausedAt;

    private LocalDateTime resumedAt;

    private String evaluateStatus;

    private String evaluateError;

    public enum InterviewPhase {
        INTRO, TECH, PROJECT, HR, COMPLETED
    }
}

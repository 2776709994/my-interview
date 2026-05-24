package com.edu.muc.app.modules.interview.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_questions")
public class InterviewQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Integer questionIndex;

    private String question;

    private String type;

    private String category;

    /** 知识点摘要（10 字以内），用于历史面试去重 */
    private String topicSummary;

    private String referenceAnswer;

    private String keyPointsJson;

    private LocalDateTime createdAt;
}

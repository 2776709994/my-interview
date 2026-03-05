package com.edu.muc.app.modules.interview.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("interview_answers")
public class InterviewAnswer {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Integer questionIndex;

    private String answer;

    private Integer score;

    private String feedback;

    private LocalDateTime evaluatedAt;

    private LocalDateTime createdAt;
}

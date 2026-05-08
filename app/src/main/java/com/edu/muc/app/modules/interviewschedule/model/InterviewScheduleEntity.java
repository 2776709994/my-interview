package com.edu.muc.app.modules.interviewschedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("interview_schedule")
public class InterviewScheduleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("company_name")
    private String companyName;

    private String position;

    @TableField("interview_time")
    private LocalDateTime interviewTime;

    @TableField("interview_type")
    private String interviewType; // ONSITE, VIDEO, PHONE

    @TableField("meeting_link")
    private String meetingLink;

    @TableField("round_number")
    private Integer roundNumber = 1;

    private String interviewer;

    private String notes;

    private String status = "PENDING";

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.edu.muc.app.modules.interviewschedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.muc.app.modules.interviewschedule.model.InterviewScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface InterviewScheduleMapper extends BaseMapper<InterviewScheduleEntity> {

    /**
     * 根据状态和面试时间查询
     */
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(
        @Param("status") String status,
        @Param("cutoff") LocalDateTime cutoff
    );

    /**
     * 根据状态查询
     */
    List<InterviewScheduleEntity> findByStatus(@Param("status") String status);

    /**
     * 查询时间范围内的面试
     */
    List<InterviewScheduleEntity> findByInterviewTimeBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    /**
     * 批量更新过期面试状态
     */
    @Update("UPDATE interview_schedule SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE status = #{oldStatus} AND interview_time < #{cutoff}")
    int updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") String newStatus,
        @Param("oldStatus") String oldStatus,
        @Param("cutoff") LocalDateTime cutoff
    );
}

package com.edu.muc.app.modules.interviewschedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.common.exception.ErrorCode;
import com.edu.muc.app.modules.interviewschedule.mapper.InterviewScheduleMapper;
import com.edu.muc.app.modules.interviewschedule.model.CreateInterviewRequest;
import com.edu.muc.app.modules.interviewschedule.model.InterviewScheduleDTO;
import com.edu.muc.app.modules.interviewschedule.model.InterviewScheduleEntity;
import com.edu.muc.app.modules.interviewschedule.model.InterviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleMapper mapper;

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(InterviewStatus.PENDING.name());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        mapper.insert(entity);
        return toDTO(entity);
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = requireEntity(
                mapper.selectOne(new LambdaQueryWrapper<InterviewScheduleEntity>()
                        .eq(InterviewScheduleEntity::getId, id)
                        .last("FOR UPDATE")), id);
        BeanUtils.copyProperties(request, entity, "id", "status", "createdAt", "updatedAt");
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    public void delete(Long id) {
        mapper.deleteById(id);
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = requireEntity(
                mapper.selectOne(new LambdaQueryWrapper<InterviewScheduleEntity>()
                        .eq(InterviewScheduleEntity::getId, id)
                        .last("FOR UPDATE")), id);
        entity.setStatus(status.name());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toDTO(entity);
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
        return mapper.selectList(new LambdaQueryWrapper<InterviewScheduleEntity>()
                .eq(status != null, InterviewScheduleEntity::getStatus, status)
                .ge(start != null, InterviewScheduleEntity::getInterviewTime, start)
                .le(end != null, InterviewScheduleEntity::getInterviewTime, end)
                .orderByAsc(InterviewScheduleEntity::getInterviewTime, InterviewScheduleEntity::getId))
            .stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(requireEntity(mapper.selectById(id), id));
    }

    private InterviewScheduleEntity requireEntity(InterviewScheduleEntity entity, Long id) {
        if (entity == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id);
        }
        return entity;
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        if (entity.getStatus() != null) {
            try {
                dto.setStatus(InterviewStatus.valueOf(entity.getStatus()));
            } catch (IllegalArgumentException e) {
                dto.setStatus(InterviewStatus.PENDING);
            }
        }
        return dto;
    }
}

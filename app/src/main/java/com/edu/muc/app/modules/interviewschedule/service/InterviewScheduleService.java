package com.edu.muc.app.modules.interviewschedule.service;

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
        InterviewScheduleEntity entity = getByIdOrThrow(id);
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
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status.name());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        return toDTO(entity);
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;

        if (start != null && end != null) {
            entities = mapper.findByInterviewTimeBetween(start, end);
        } else if (status != null) {
            entities = mapper.findByStatus(status);
        } else {
            entities = mapper.selectList(null);
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        InterviewScheduleEntity entity = mapper.selectById(id);
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

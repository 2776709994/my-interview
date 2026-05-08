package com.edu.muc.app.modules.interviewschedule;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.modules.interviewschedule.model.CreateInterviewRequest;
import com.edu.muc.app.modules.interviewschedule.model.InterviewScheduleDTO;
import com.edu.muc.app.modules.interviewschedule.model.InterviewStatus;
import com.edu.muc.app.modules.interviewschedule.model.ParseRequest;
import com.edu.muc.app.modules.interviewschedule.model.ParseResponse;
import com.edu.muc.app.modules.interviewschedule.service.InterviewParseService;
import com.edu.muc.app.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

    private final InterviewScheduleService scheduleService;
    private final InterviewParseService parseService;

    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        log.info("接收到解析请求，来源: {}", request.getSource());
        ParseResponse response = parseService.parse(request.getRawText(), request.getSource());
        return Result.success(response);
    }

    @PostMapping
    public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("创建面试记录: {} - {}", request.getCompanyName(), request.getPosition());
        InterviewScheduleDTO dto = scheduleService.create(request);
        return Result.success(dto);
    }

    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
        InterviewScheduleDTO dto = scheduleService.getById(id);
        return Result.success(dto);
    }

    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        List<InterviewScheduleDTO> list = scheduleService.getAll(status, start, end);
        return Result.success(list);
    }

    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(
        @PathVariable Long id,
        @Valid @RequestBody CreateInterviewRequest request
    ) {
        log.info("更新面试记录: ID={}", id);
        InterviewScheduleDTO dto = scheduleService.update(id, request);
        return Result.success(dto);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除面试记录: ID={}", id);
        scheduleService.delete(id);
        return Result.success(null);
    }

    @RequestMapping(path = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<InterviewScheduleDTO> updateStatus(
        @PathVariable Long id,
        @RequestParam InterviewStatus status
    ) {
        log.info("更新面试状态: ID={}, status={}", id, status);
        InterviewScheduleDTO dto = scheduleService.updateStatus(id, status);
        return Result.success(dto);
    }
}

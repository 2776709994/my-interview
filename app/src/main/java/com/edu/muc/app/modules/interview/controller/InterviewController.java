package com.edu.muc.app.modules.interview.controller;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.infrastructure.export.PdfExportService;
import com.edu.muc.app.modules.interview.dto.*;
import com.edu.muc.app.modules.interview.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final PdfExportService pdfExportService;

    /**
     * 获取技能标签列表（用于选择面试方向）
     */
    @GetMapping("/skills")
    public Result<List<SkillDTO>> getSkills() {
        // 模拟一个完整的 SkillDTO 结构，匹配前端类型定义
        SkillDTO skill = new SkillDTO();
        skill.setId("java-backend");
        skill.setName("Java后端开发");
        skill.setDescription("针对Java后端工程师的专项面试");
        skill.setPreset(true);
        
        SkillDTO.CategoryDTO cat = new SkillDTO.CategoryDTO();
        cat.setKey("java-core");
        cat.setLabel("Java核心");
        cat.setPriority("CORE");
        
        skill.setCategories(List.of(cat));
        
        return Result.success(List.of(skill));
    }

    /**
     * 列出所有文字面试会话
     */
    @GetMapping("/sessions")
    public Result<List<TextSessionMetaDTO>> listSessions() {
        List<TextSessionMetaDTO> sessions = interviewService.listSessions();
        return Result.success(sessions);
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/sessions")
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("🎯 创建面试会话请求: skillId={}, questionCount={}",
                request.getSkillId(), request.getQuestionCount());

        InterviewSessionDTO session = interviewService.createSession(request);
        return Result.success(session);
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = interviewService.getSession(sessionId);
        return Result.success(session);
    }

    /**
     * 获取会话详情（别名接口，与前端对齐）
     * 返回完整的面试评估报告，包含问题、答案、评分等
     */
    @GetMapping("/sessions/{sessionId}/details")
    public Result<InterviewReportDTO> getSessionDetails(@PathVariable String sessionId) {
        // 直接调用 getReport，返回完整的评估数据
        InterviewReportDTO report = interviewService.getReport(sessionId);
        return Result.success(report);
    }

    /**
     * 查找未完成的面试会话
     */
    @GetMapping("/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        InterviewSessionDTO session = interviewService.findUnfinishedSession(resumeId);
        if (session != null) {
            return Result.success(session);
        } else {
            return Result.error(404, "没有未完成的会话");
        }
    }

    /**
     * 获取当前问题
     */
    @GetMapping("/sessions/{sessionId}/question")
    public Result<CurrentQuestionResponse> getCurrentQuestion(@PathVariable String sessionId) {
        CurrentQuestionResponse response = interviewService.getCurrentQuestion(sessionId);
        return Result.success(response);
    }

    /**
     * 提交答案
     */
    @PostMapping("/sessions/{sessionId}/answers")
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody SubmitAnswerRequest request) {
        request.setSessionId(sessionId);
        SubmitAnswerResponse response = interviewService.submitAnswer(request);
        return Result.success(response);
    }

    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody SubmitAnswerRequest request) {
        request.setSessionId(sessionId);
        interviewService.saveAnswer(request);
        return Result.success();
    }

    /**
     * 提前交卷
     */
    @PostMapping("/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        interviewService.completeInterview(sessionId);
        return Result.success();
    }

    /**
     * 获取面试报告
     */
    @GetMapping("/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        InterviewReportDTO report = interviewService.getReport(sessionId);
        return Result.success(report);
    }

    /**
     * 导出面试评估报告 PDF（iText 8）
     */
    @GetMapping("/sessions/{sessionId}/report/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable String sessionId) {
        InterviewReportDTO report = interviewService.getReport(sessionId);
        byte[] pdf = pdfExportService.exportInterviewReport(report);
        String filename = URLEncoder.encode("面试评估报告.pdf", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .body(pdf);
    }

    /**
     * 删除面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<String> deleteSession(@PathVariable String sessionId) {
        boolean success = interviewService.deleteSession(sessionId);
        if (success) {
            return Result.success("面试会话删除成功");
        } else {
            return Result.error(404, "面试会话不存在");
        }
    }
}

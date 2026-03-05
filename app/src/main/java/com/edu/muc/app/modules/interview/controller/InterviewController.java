package com.edu.muc.app.modules.interview.controller;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.modules.interview.dto.*;
import com.edu.muc.app.modules.interview.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

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
        try {
            List<TextSessionMetaDTO> sessions = interviewService.listSessions();
            return Result.success(sessions);
        } catch (Exception e) {
            log.error("❌ 获取会话列表失败", e);
            return Result.error(500, "获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/sessions")
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        try {
            log.info("🎯 创建面试会话请求: skillId={}, questionCount={}", 
                    request.getSkillId(), request.getQuestionCount());
            
            InterviewSessionDTO session = interviewService.createSession(request);
            return Result.success(session);
        } catch (Exception e) {
            log.error("❌ 创建会话失败", e);
            return Result.error(500, "创建会话失败: " + e.getMessage());
        }
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        try {
            InterviewSessionDTO session = interviewService.getSession(sessionId);
            return Result.success(session);
        } catch (Exception e) {
            log.error(" 获取会话失败: {}", sessionId, e);
            return Result.error(500, "获取会话失败: " + e.getMessage());
        }
    }

    /**
     * 获取会话详情（别名接口，与前端对齐）
     * 返回完整的面试评估报告，包含问题、答案、评分等
     */
    @GetMapping("/sessions/{sessionId}/details")
    public Result<InterviewReportDTO> getSessionDetails(@PathVariable String sessionId) {
        try {
            // 直接调用 getReport，返回完整的评估数据
            InterviewReportDTO report = interviewService.getReport(sessionId);
            return Result.success(report);
        } catch (Exception e) {
            log.error("❌ 获取会话详情失败: {}", sessionId, e);
            return Result.error(500, "获取详情失败: " + e.getMessage());
        }
    }

    /**
     * 查找未完成的面试会话
     */
    @GetMapping("/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        try {
            InterviewSessionDTO session = interviewService.findUnfinishedSession(resumeId);
            if (session != null) {
                return Result.success(session);
            } else {
                return Result.error(404, "没有未完成的会话");
            }
        } catch (Exception e) {
            log.error("❌ 查找未完成会话失败: {}", resumeId, e);
            return Result.error(500, "查找失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前问题
     */
    @GetMapping("/sessions/{sessionId}/question")
    public Result<CurrentQuestionResponse> getCurrentQuestion(@PathVariable String sessionId) {
        try {
            CurrentQuestionResponse response = interviewService.getCurrentQuestion(sessionId);
            return Result.success(response);
        } catch (Exception e) {
            log.error("❌ 获取当前问题失败: {}", sessionId, e);
            return Result.error(500, "获取问题失败: " + e.getMessage());
        }
    }

    /**
     * 提交答案
     */
    @PostMapping("/sessions/{sessionId}/answers")
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody SubmitAnswerRequest request) {
        try {
            request.setSessionId(sessionId);
            SubmitAnswerResponse response = interviewService.submitAnswer(request);
            return Result.success(response);
        } catch (Exception e) {
            log.error("❌ 提交答案失败: {}", sessionId, e);
            return Result.error(500, "提交答案失败: " + e.getMessage());
        }
    }

    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody SubmitAnswerRequest request) {
        try {
            request.setSessionId(sessionId);
            interviewService.saveAnswer(request);
            return Result.success();
        } catch (Exception e) {
            log.error("❌ 暂存答案失败: {}", sessionId, e);
            return Result.error(500, "暂存答案失败: " + e.getMessage());
        }
    }

    /**
     * 提前交卷
     */
    @PostMapping("/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        try {
            interviewService.completeInterview(sessionId);
            return Result.success();
        } catch (Exception e) {
            log.error("❌ 提前交卷失败: {}", sessionId, e);
            return Result.error(500, "交卷失败: " + e.getMessage());
        }
    }

    /**
     * 获取面试报告
     */
    @GetMapping("/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        try {
            InterviewReportDTO report = interviewService.getReport(sessionId);
            return Result.success(report);
        } catch (Exception e) {
            log.error(" 获取报告失败: {}", sessionId, e);
            return Result.error(500, "获取报告失败: " + e.getMessage());
        }
    }

    /**
     * 导出面试报告（PDF）
     */
    @GetMapping("/sessions/{sessionId}/export")
    public void exportReport(@PathVariable String sessionId, 
                            jakarta.servlet.http.HttpServletResponse response) {
        try {
            // TODO: 实现 PDF 导出逻辑
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", 
                    "attachment; filename=interview-report-" + sessionId + ".pdf");
            log.info(" 导出面试报告: {}", sessionId);
        } catch (Exception e) {
            log.error("❌ 导出报告失败: {}", sessionId, e);
        }
    }

    /**
     * 删除面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<String> deleteSession(@PathVariable String sessionId) {
        try {
            boolean success = interviewService.deleteSession(sessionId);
            if (success) {
                return Result.success("面试会话删除成功");
            } else {
                return Result.error(404, "面试会话不存在");
            }
        } catch (Exception e) {
            log.error("❌ 删除会话失败: {}", sessionId, e);
            return Result.error(500, "删除会话失败: " + e.getMessage());
        }
    }
}

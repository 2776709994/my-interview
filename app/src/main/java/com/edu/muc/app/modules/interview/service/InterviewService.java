package com.edu.muc.app.modules.interview.service;

import com.edu.muc.app.modules.interview.dto.*;

import java.util.List;

public interface InterviewService {
    
    /**
     * 创建面试会话并生成问题
     */
    InterviewSessionDTO createSession(CreateInterviewRequest request);
    
    /**
     * 获取会话信息
     */
    InterviewSessionDTO getSession(String sessionId);
    
    /**
     * 获取当前问题
     */
    CurrentQuestionResponse getCurrentQuestion(String sessionId);
    
    /**
     * 提交答案
     */
    SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request);
    
    /**
     * 暂存答案
     */
    void saveAnswer(SubmitAnswerRequest request);
    
    /**
     * 获取面试报告
     */
    InterviewReportDTO getReport(String sessionId);
    
    /**
     * 列出所有会话
     */
    List<TextSessionMetaDTO> listSessions();
    
    /**
     * 查找未完成的会话
     */
    InterviewSessionDTO findUnfinishedSession(Long resumeId);
    
    /**
     * 提前交卷
     */
    void completeInterview(String sessionId);
    
    /**
     * 删除面试会话（级联删除问题和答案）
     */
    boolean deleteSession(String sessionId);
}

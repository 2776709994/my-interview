package com.edu.muc.app.infrastructure.export;

import com.edu.muc.app.modules.interview.dto.InterviewReportDTO;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 导出冒烟测试：验证 iText 8 + 中文字体链路可用（纯内存，不依赖外部设施）
 */
class PdfExportServiceTest {

    private final PdfExportService pdfExportService = new PdfExportService();

    @Test
    void exportInterviewReport_shouldProducePdfBytes() {
        InterviewReportDTO report = new InterviewReportDTO();
        report.setSessionId("test-session");
        report.setTotalQuestions(2);
        report.setOverallScore(82);
        report.setOverallFeedback("整体表现良好，基础知识扎实。");
        report.setStrengths(List.of("基础概念清晰", "项目经验贴合岗位"));
        report.setImprovements(List.of("加深对 JVM 调优的理解"));
        InterviewReportDTO.QuestionEvaluationDTO q = new InterviewReportDTO.QuestionEvaluationDTO();
        q.setQuestionIndex(0);
        q.setQuestion("谈谈 HashMap 的扩容机制");
        q.setCategory("Java 基础");
        q.setUserAnswer("负载因子超过阈值时触发 resize");
        q.setScore(85);
        q.setFeedback("回答到位，可补充并发场景");
        report.setQuestionDetails(List.of(q));

        byte[] pdf = pdfExportService.exportInterviewReport(report);

        assertNotNull(pdf);
        // PDF 文件头魔数
        assertTrue(pdf.length > 4 && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "导出内容应为合法 PDF 字节流");
    }

    @Test
    void exportResumeAnalysis_shouldProducePdfBytes() {
        ResumeDetailDTO.AnalysisDTO analysis = new ResumeDetailDTO.AnalysisDTO();
        analysis.setOverallScore(76);
        analysis.setSkillMatchScore(20);
        analysis.setContentScore(19);
        analysis.setStructureScore(16);
        analysis.setExpressionScore(11);
        analysis.setProjectScore(10);
        analysis.setSummary("简历结构清晰，项目描述具体。");
        analysis.setStrengths(List.of("技术栈与目标岗位匹配"));
        ResumeDetailDTO.SuggestionDTO suggestion = new ResumeDetailDTO.SuggestionDTO();
        suggestion.setCategory("内容");
        suggestion.setPriority("高");
        suggestion.setIssue("缺少量化成果");
        suggestion.setRecommendation("为项目补充 QPS、覆盖率等量化指标");
        analysis.setSuggestions(List.of(suggestion));

        byte[] pdf = pdfExportService.exportResumeAnalysis("我的简历.pdf", analysis);

        assertNotNull(pdf);
        assertTrue(pdf.length > 4 && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "导出内容应为合法 PDF 字节流");
    }
}

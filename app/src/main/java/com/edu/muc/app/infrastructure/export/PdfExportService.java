package com.edu.muc.app.infrastructure.export;

import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.modules.interview.dto.InterviewReportDTO;
import com.edu.muc.app.modules.resume.dto.ResumeDetailDTO;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * PDF 导出服务（iText 8）
 * <p>
 * 支持导出模拟面试评估报告与简历分析报告。中文渲染优先使用项目内嵌开源字体
 * （朱雀仿宋，OFL 协议，FORCE_EMBEDDED 保证跨端显示一致），缺失时降级为
 * font-asian 内置的 STSong 字体。
 * </p>
 */
@Slf4j
@Service
public class PdfExportService {

    private static final DeviceRgb HEADER_COLOR = new DeviceRgb(41, 128, 185);
    private static final DeviceRgb SECTION_COLOR = new DeviceRgb(52, 73, 94);
    private static final DeviceRgb GREEN = new DeviceRgb(39, 174, 96);
    private static final DeviceRgb YELLOW = new DeviceRgb(241, 196, 15);
    private static final DeviceRgb RED = new DeviceRgb(231, 76, 60);

    /**
     * 创建支持中文的字体（内嵌字体优先，STSong 兜底）
     */
    private PdfFont createChineseFont() {
        try (var fontStream = getClass().getClassLoader()
                .getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")) {
            if (fontStream != null) {
                byte[] fontBytes = fontStream.readAllBytes();
                log.debug("使用项目内嵌字体: fonts/ZhuqueFangsong-Regular.ttf");
                return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H,
                        EmbeddingStrategy.FORCE_EMBEDDED);
            }
            log.warn("未找到内嵌字体文件，降级使用 font-asian STSong 字体");
            return PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建中文字体失败: {}", e.getMessage(), e);
            throw new BusinessException("PDF_FONT_ERROR", "创建PDF字体失败: " + e.getMessage());
        }
    }

    /**
     * 清理文本中可能导致字体问题的字符（如 emoji 等 Symbol/代理区字符）
     */
    private String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }

    /**
     * 导出面试评估报告为 PDF
     */
    public byte[] exportInterviewReport(InterviewReportDTO report) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
            Document document = new Document(pdfDoc);
            document.setFont(createChineseFont());

            // 标题
            document.add(new Paragraph("模拟面试评估报告")
                    .setFontSize(24)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(HEADER_COLOR));

            // 基本信息
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("面试信息"));
            document.add(new Paragraph("会话ID: " + report.getSessionId()));
            document.add(new Paragraph("题目数量: " + report.getTotalQuestions()));

            // 总分
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("综合评分"));
            document.add(new Paragraph("总分: " + report.getOverallScore() + " / 100")
                    .setFontSize(18)
                    .setBold()
                    .setFontColor(getScoreColor(report.getOverallScore())));

            // 总体评价
            if (report.getOverallFeedback() != null) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle("总体评价"));
                document.add(new Paragraph(sanitizeText(report.getOverallFeedback())));
            }

            // 优势
            appendBulletSection(document, "表现优势", report.getStrengths());

            // 改进建议
            appendBulletSection(document, "改进建议", report.getImprovements());

            // 问答详情
            List<InterviewReportDTO.QuestionEvaluationDTO> details = report.getQuestionDetails();
            if (details != null && !details.isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle("问答详情"));
                for (InterviewReportDTO.QuestionEvaluationDTO q : details) {
                    document.add(new Paragraph("\n"));
                    document.add(new Paragraph("问题 " + (q.getQuestionIndex() + 1)
                            + " [" + (q.getCategory() != null ? q.getCategory() : "综合") + "]")
                            .setBold()
                            .setFontSize(12));
                    document.add(new Paragraph("Q: " + sanitizeText(q.getQuestion())));
                    document.add(new Paragraph("A: " + sanitizeText(
                            q.getUserAnswer() != null ? q.getUserAnswer() : "未回答")));
                    document.add(new Paragraph("得分: " + (int) q.getScore() + "/100")
                            .setFontColor(getScoreColor((int) q.getScore())));
                    if (q.getFeedback() != null) {
                        document.add(new Paragraph("评价: " + sanitizeText(q.getFeedback()))
                                .setItalic());
                    }
                }
            }

            // 参考答案
            if (report.getReferenceAnswers() != null && !report.getReferenceAnswers().isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle("参考答案"));
                for (InterviewReportDTO.ReferenceAnswerDTO ra : report.getReferenceAnswers()) {
                    document.add(new Paragraph("\n"));
                    document.add(new Paragraph("问题 " + (ra.getQuestionIndex() + 1)).setBold());
                    document.add(new Paragraph(sanitizeText(ra.getReferenceAnswer()))
                            .setFontColor(GREEN));
                    if (ra.getKeyPoints() != null && !ra.getKeyPoints().isEmpty()) {
                        for (String point : ra.getKeyPoints()) {
                            document.add(new Paragraph("• " + sanitizeText(point)));
                        }
                    }
                }
            }

            document.close();
            return baos.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出面试报告失败: sessionId={}", report.getSessionId(), e);
            throw new BusinessException("EXPORT_PDF_FAILED", "PDF导出失败: " + e.getMessage());
        }
    }

    /**
     * 导出简历分析报告为 PDF
     */
    public byte[] exportResumeAnalysis(String filename, ResumeDetailDTO.AnalysisDTO analysis) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfDocument pdfDoc = new PdfDocument(new PdfWriter(baos));
            Document document = new Document(pdfDoc);
            document.setFont(createChineseFont());

            // 标题
            document.add(new Paragraph("简历分析报告")
                    .setFontSize(24)
                    .setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontColor(HEADER_COLOR));

            // 基本信息
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("基本信息"));
            document.add(new Paragraph("文件名: " + sanitizeText(filename)));

            // 总分
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("综合评分"));
            int overall = analysis.getOverallScore() != null ? analysis.getOverallScore() : 0;
            document.add(new Paragraph("总分: " + overall + " / 100")
                    .setFontSize(18)
                    .setBold()
                    .setFontColor(getScoreColor(overall)));

            // 五维评分
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("五维评分"));
            Table scoreTable = new Table(UnitValue.createPercentArray(new float[]{2, 1}))
                    .useAllAvailableWidth();
            addScoreRow(scoreTable, "技能匹配度", analysis.getSkillMatchScore(), 25);
            addScoreRow(scoreTable, "内容完整性", analysis.getContentScore(), 25);
            addScoreRow(scoreTable, "结构清晰度", analysis.getStructureScore(), 20);
            addScoreRow(scoreTable, "表达专业性", analysis.getExpressionScore(), 15);
            addScoreRow(scoreTable, "项目经验", analysis.getProjectScore(), 15);
            document.add(scoreTable);

            // 简历摘要
            if (analysis.getSummary() != null) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle("简历摘要"));
                document.add(new Paragraph(sanitizeText(analysis.getSummary())));
            }

            // 优势亮点
            appendBulletSection(document, "优势亮点", analysis.getStrengths());

            // 改进建议
            List<ResumeDetailDTO.SuggestionDTO> suggestions = analysis.getSuggestions();
            if (suggestions != null && !suggestions.isEmpty()) {
                document.add(new Paragraph("\n"));
                document.add(createSectionTitle("改进建议"));
                for (ResumeDetailDTO.SuggestionDTO suggestion : suggestions) {
                    document.add(new Paragraph("【" + sanitizeText(suggestion.getPriority()) + "】"
                            + sanitizeText(suggestion.getCategory())).setBold());
                    document.add(new Paragraph("问题: " + sanitizeText(suggestion.getIssue())));
                    document.add(new Paragraph("建议: " + sanitizeText(suggestion.getRecommendation())));
                    document.add(new Paragraph("\n"));
                }
            }

            document.close();
            return baos.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出简历分析报告失败: filename={}", filename, e);
            throw new BusinessException("EXPORT_PDF_FAILED", "PDF导出失败: " + e.getMessage());
        }
    }

    // ========== 通用片段 ==========

    private void appendBulletSection(Document document, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle(title));
        for (String item : items) {
            document.add(new Paragraph("• " + sanitizeText(item)));
        }
    }

    private Paragraph createSectionTitle(String title) {
        return new Paragraph(title)
                .setFontSize(14)
                .setBold()
                .setFontColor(SECTION_COLOR)
                .setMarginTop(10);
    }

    private void addScoreRow(Table table, String dimension, Integer score, int maxScore) {
        int value = score != null ? score : 0;
        table.addCell(new Cell().add(new Paragraph(dimension)));
        table.addCell(new Cell().add(new Paragraph(value + " / " + maxScore)
                .setFontColor(getScoreColor(value * 100 / maxScore))));
    }

    private DeviceRgb getScoreColor(int score) {
        if (score >= 80) {
            return GREEN;
        }
        if (score >= 60) {
            return YELLOW;
        }
        return RED;
    }
}

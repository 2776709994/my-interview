package com.edu.muc.app.modules.resume.listener;

import com.edu.muc.app.common.ai.StructuredOutputInvoker;
import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.exception.ErrorCode;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.infrastructure.file.DocumentParseService;
import com.edu.muc.app.infrastructure.file.MinioFileStorageService;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.edu.muc.app.modules.resume.model.ResumeAnalysisResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 简历分析异步消费者（Redis Stream 模板方法实现）
 * <p>
 * 状态机：PENDING →（领取，带状态守卫）→ PROCESSING → COMPLETED/FAILED（超过 3 次重试）。
 * 业务流程：下载 MinIO 文件 → Tika 解析 → AI 分析 → 保存五维评分与改进建议。
 * </p>
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<Long> {

    private final ResumesMapper resumesMapper;
    private final ResumeAnalysesMapper analysesMapper;
    private final ChatClient chatClient;
    private final MinioFileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final ExecutorService analysisExecutor;
    private final TransactionTemplate transactionTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 结构化输出转换器：将大模型响应按 JSON Schema 反序列化为五维评分（线程安全，可静态复用） */
    private static final BeanOutputConverter<ResumeAnalysisResponse> ANALYSIS_OUTPUT_CONVERTER =
            new BeanOutputConverter<>(ResumeAnalysisResponse.class);

    public AnalyzeStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                 StreamPendingRecoverer pendingRecoverer,
                                 ResumesMapper resumesMapper,
                                 ResumeAnalysesMapper analysesMapper,
                                 MinioFileStorageService fileStorageService,
                                 ChatClient chatClient,
                                 DocumentParseService documentParseService,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 TransactionTemplate transactionTemplate,
                                 @Qualifier("resumeAnalysisExecutor") ExecutorService analysisExecutor) {
        super(redisTemplate, pendingRecoverer);
        this.resumesMapper = resumesMapper;
        this.analysesMapper = analysesMapper;
        this.fileStorageService = fileStorageService;
        this.chatClient = chatClient;
        this.documentParseService = documentParseService;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.transactionTemplate = transactionTemplate;
        this.analysisExecutor = analysisExecutor;
    }

    @Override
    protected ExecutorService executor() {
        return analysisExecutor;
    }

    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Long resumeId) {
        return Map.of(AsyncTaskStreamConstants.FIELD_RESUME_ID, String.valueOf(resumeId));
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "resume-analyze-listener";
    }

    @Override
    protected Long parsePayload(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue().get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        return raw == null ? null : Long.parseLong(raw.toString().trim());
    }

    @Override
    protected String payloadIdentifier(Long resumeId) {
        return "resumeId=" + resumeId;
    }

    @Override
    protected boolean shouldSkip(Long resumeId) {
        Resumes resume = resumesMapper.selectById(resumeId);
        return resume == null || AsyncTaskStatus.COMPLETED.name().equals(resume.getAnalyzeStatus());
    }

    @Override
    protected boolean tryMarkProcessing(Long resumeId) {
        // 状态守卫：仅 PENDING/PROCESSING（重试/宕机重投）允许领取，防止重复消息触发并发分析
        return resumesMapper.update(null, new LambdaUpdateWrapper<Resumes>()
                .eq(Resumes::getId, resumeId)
                .in(Resumes::getAnalyzeStatus,
                        AsyncTaskStatus.PENDING.name(), AsyncTaskStatus.PROCESSING.name())
                .set(Resumes::getAnalyzeStatus, AsyncTaskStatus.PROCESSING.name())) > 0;
    }

    @Override
    protected void processBusiness(Long resumeId) throws Exception {
        log.info("🚀 开始处理简历分析，简历ID: {}", resumeId);

        Resumes resume = resumesMapper.selectById(resumeId);
        if (resume == null) {
            // 不静默丢弃：抛异常走自动重试，避免上传事务提交延迟导致任务永久丢失
            throw new IllegalStateException("简历不存在: " + resumeId);
        }
        log.info("✅ 查询到简历信息，文件名: {}", resume.getOriginalFilename());

        // 1. 提取文本（缺失时从 MinIO 下载并解析；失败抛异常走自动重试）
        String text = ensureResumeText(resume);

        // 3. 加载提示词模板（动态注入当前时间，解决大模型时间幻觉问题）
        PromptTemplate systemTemplate = new PromptTemplate(
                new DefaultResourceLoader().getResource("classpath:prompts/resume-analysis-system.st"));
        PromptTemplate userTemplate = new PromptTemplate(
                new DefaultResourceLoader().getResource("classpath:prompts/resume-analysis-user.st"));
        java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy年M月");
        String currentTime = LocalDateTime.now().format(formatter);
        String systemPrompt = systemTemplate.render(Map.of("currentTime", currentTime));
        String userPrompt = userTemplate.render(Map.of("resumeText", text));
        log.info("📝 提示词模板加载完成，当前时间: {}, 用户提示词长度: {}", currentTime, userPrompt.length());

        // 4. 结构化输出：BeanOutputConverter 生成 JSON Schema 附加到系统提示，
        //    StructuredOutputInvoker 统一调用与解析失败重试（含本地修复未转义引号）
        log.info("🤖 开始调用 AI 分析简历，简历ID: {}", resumeId);
        String systemPromptWithFormat = systemPrompt + "\n\n" + ANALYSIS_OUTPUT_CONVERTER.getFormat();
        ResumeAnalysisResponse response = structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, ANALYSIS_OUTPUT_CONVERTER,
                ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析结构化输出失败: ", "resume-analysis", log);
        log.info("✅ AI 结构化分析完成: overallScore={}", response.overallScore());

        ResumeAnalysisResponse.ScoreDetail detail = response.scoreDetail();
        if (detail == null) {
            throw new IllegalStateException("简历分析缺少五维评分");
        }
        String strengths = MAPPER.writeValueAsString(response.strengths() != null ? response.strengths() : List.of());
        String suggestions = MAPPER.writeValueAsString(response.suggestions() != null ? response.suggestions() : List.of());
        transactionTemplate.executeWithoutResult(ignored -> {
            Resumes current = resumesMapper.selectOne(new LambdaQueryWrapper<Resumes>()
                    .eq(Resumes::getId, resumeId)
                    .last("FOR UPDATE"));
            if (current == null || !AsyncTaskStatus.PROCESSING.name().equals(current.getAnalyzeStatus())) {
                return;
            }
            ResumeAnalyses analysis = analysesMapper.selectOne(new LambdaQueryWrapper<ResumeAnalyses>()
                    .eq(ResumeAnalyses::getResumeId, resumeId)
                    .orderByDesc(ResumeAnalyses::getAnalyzedAt, ResumeAnalyses::getId)
                    .last("LIMIT 1"));
            if (analysis == null) {
                analysis = new ResumeAnalyses();
                analysis.setResumeId(resumeId);
            }
            analysis.setOverallScore(detail.total());
            analysis.setProjectScore(detail.projectScore());
            analysis.setSkillMatchScore(detail.skillMatchScore());
            analysis.setContentScore(detail.contentScore());
            analysis.setStructureScore(detail.structureScore());
            analysis.setExpressionScore(detail.expressionScore());
            analysis.setSummary(response.summary());
            analysis.setStrengthsJson(strengths);
            analysis.setSuggestionsJson(suggestions);
            analysis.setAnalyzedAt(LocalDateTime.now());
            analysesMapper.insertOrUpdate(analysis);
            resumesMapper.update(null, new LambdaUpdateWrapper<Resumes>()
                    .eq(Resumes::getId, resumeId)
                    .set(Resumes::getAnalyzeStatus, AsyncTaskStatus.COMPLETED.name())
                    .set(Resumes::getAnalyzeError, null)
                    .setSql("access_count = COALESCE(access_count, 0) + 1")
                    .set(Resumes::getLastAccessedAt, new Date()));
        });
    }

    @Override
    protected void markCompleted(Long resumeId) {
        // 评分与完成状态在同一个事务中提交。
    }

    @Override
    protected void markFailed(Long resumeId, String error) {
        log.error("❌ 简历分析最终失败，简历ID: {}, 原因: {}", resumeId, error);
        resumesMapper.update(null, new LambdaUpdateWrapper<Resumes>()
                .eq(Resumes::getId, resumeId)
                .eq(Resumes::getAnalyzeStatus, AsyncTaskStatus.PROCESSING.name())
                .set(Resumes::getAnalyzeStatus, AsyncTaskStatus.FAILED.name())
                .set(Resumes::getAnalyzeError, error));
    }

    /**
     * 确保简历文本可用：缺失时从 MinIO 下载并 Tika 解析（30 秒超时），成功后回写数据库。
     * 解析失败/空文本（疑似扫描件）抛异常，走自动重试与 FAILED 终态。
     */
    private String ensureResumeText(Resumes resume) throws Exception {
        Long resumeId = resume.getId();
        String text = resume.getResumeText();
        if (text != null && !text.isBlank()) {
            log.info("✅ 使用已有的简历文本，长度: {}", text.length());
            return text;
        }

        log.info("📄 简历文本为空，开始从 MinIO 下载并解析: {}", resume.getStorageKey());
        byte[] fileBytes = fileStorageService.downloadAsBytes(resume.getStorageKey());
        log.info("✅ 文件下载成功，字节数组长度: {} bytes", fileBytes.length);

        // 超时后中断解析线程。
        // 专业解析：PDF 禁用图片提取 + 按坐标排序、DOCX 禁用嵌入资源、正则清洗噪声
        Future<String> future = analysisExecutor.submit(
                () -> documentParseService.parseContent(fileBytes, resume.getOriginalFilename()));
        try {
            text = future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("文本提取失败：解析超时（超过30秒）", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("文本提取失败：" + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }

        if (text == null || text.isBlank()) {
            throw new IllegalStateException("PDF 解析结果为空，可能是扫描件（图片格式），无法提取文字");
        }
        log.info("✅ Tika 解析成功，文本长度: {}", text.length());

        resumesMapper.update(null, new LambdaUpdateWrapper<Resumes>()
                .eq(Resumes::getId, resumeId)
                .set(Resumes::getResumeText, text));
        log.info("💾 简历文本已保存到数据库");
        return text;
    }


}

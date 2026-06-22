package com.edu.muc.app.modules.resume.listener;

import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.constant.AsyncTaskStreamConstants;
import com.edu.muc.app.common.model.AsyncTaskStatus;
import com.edu.muc.app.infrastructure.file.DocumentParseService;
import com.edu.muc.app.infrastructure.file.MinioFileStorageService;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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
    private final ExecutorService analysisExecutor;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AnalyzeStreamConsumer(RedisTemplate<String, Object> redisTemplate,
                                 StreamPendingRecoverer pendingRecoverer,
                                 ResumesMapper resumesMapper,
                                 ResumeAnalysesMapper analysesMapper,
                                 MinioFileStorageService fileStorageService,
                                 ChatClient chatClient,
                                 DocumentParseService documentParseService,
                                 @Qualifier("resumeAnalysisExecutor") ExecutorService analysisExecutor) {
        super(redisTemplate, pendingRecoverer);
        this.resumesMapper = resumesMapper;
        this.analysesMapper = analysesMapper;
        this.fileStorageService = fileStorageService;
        this.chatClient = chatClient;
        this.documentParseService = documentParseService;
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
        return resume != null && AsyncTaskStatus.COMPLETED.name().equals(resume.getAnalyzeStatus());
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

        // 2. 获取分析记录（重试时复用已有记录，避免重复插入）
        ResumeAnalyses analysis = analysesMapper.selectOne(
                new LambdaQueryWrapper<ResumeAnalyses>().eq(ResumeAnalyses::getResumeId, resumeId));
        if (analysis == null) {
            analysis = new ResumeAnalyses();
            analysis.setResumeId(resumeId);
            analysis.setAnalyzedAt(LocalDateTime.now());
            analysesMapper.insert(analysis);
            log.info("📋 创建分析记录: id={}", analysis.getId());
        }

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

        // 4. 调用 AI 分析（网络超时指数退避重试：2s/4s/8s）
        log.info("🤖 开始调用 AI 分析简历，简历ID: {}", resumeId);
        String aiResponse = invokeAiWithRetry(systemPrompt, userPrompt);
        log.info("✅ AI 分析完成，响应长度: {}", aiResponse != null ? aiResponse.length() : 0);

        // 5. 解析 JSON 并保存五维评分
        String jsonStr = aiResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```", "").trim();
        }
        JsonNode root = MAPPER.readTree(jsonStr);

        int overallScore = root.path("overallScore").asInt(0);
        String summary = root.path("summary").asText("");
        JsonNode strengthsNode = root.has("strengths") ? root.get("strengths") : MAPPER.createArrayNode();
        JsonNode suggestionsNode = root.has("suggestions") ? root.get("suggestions") : MAPPER.createArrayNode();
        JsonNode scoreDetail = root.path("scoreDetail");

        analysis.setOverallScore(overallScore);
        analysis.setProjectScore(scoreDetail.path("projectScore").asInt(0));
        analysis.setSkillMatchScore(scoreDetail.path("skillMatchScore").asInt(0));
        analysis.setContentScore(scoreDetail.path("contentScore").asInt(0));
        analysis.setStructureScore(scoreDetail.path("structureScore").asInt(0));
        analysis.setExpressionScore(scoreDetail.path("expressionScore").asInt(0));
        analysis.setSummary(summary);
        analysis.setStrengthsJson(strengthsNode.toString());
        analysis.setSuggestionsJson(suggestionsNode.toString());
        analysis.setAnalyzedAt(LocalDateTime.now());
        analysesMapper.updateById(analysis);
        log.info("💾 分析结果已保存，简历ID: {}, 总分: {}", resumeId, overallScore);
    }

    @Override
    protected void markCompleted(Long resumeId) {
        Resumes resume = resumesMapper.selectById(resumeId);
        if (resume != null) {
            resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED.name());
            resume.setAccessCount((resume.getAccessCount() != null ? resume.getAccessCount() : 0) + 1);
            resume.setLastAccessedAt(new Date());
            resumesMapper.updateById(resume);
            log.info("🎉 简历分析完成！简历ID: {}", resumeId);
        }
    }

    @Override
    protected void markFailed(Long resumeId, String error) {
        log.error("❌ 简历分析最终失败，简历ID: {}, 原因: {}", resumeId, error);
        Resumes resume = resumesMapper.selectById(resumeId);
        if (resume != null) {
            resume.setAnalyzeStatus(AsyncTaskStatus.FAILED.name());
            resume.setAnalyzeError(error);
            resumesMapper.updateById(resume);
        }
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

        // CompletableFuture 实现超时控制，避免创建临时线程池
        // 专业解析：PDF 禁用图片提取 + 按坐标排序、DOCX 禁用嵌入资源、正则清洗噪声
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> documentParseService.parseContent(fileBytes, resume.getOriginalFilename()),
                analysisExecutor);
        try {
            text = future.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("文本提取失败：解析超时（超过30秒）", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("文本提取失败：" + e.getCause().getMessage(), e.getCause());
        }

        if (text == null || text.isBlank()) {
            throw new IllegalStateException("PDF 解析结果为空，可能是扫描件（图片格式），无法提取文字");
        }
        log.info("✅ Tika 解析成功，文本长度: {}", text.length());

        resume.setResumeText(text);
        resumesMapper.updateById(resume);
        log.info("💾 简历文本已保存到数据库");
        return text;
    }

    /**
     * 调用 AI 分析，网络类异常指数退避重试 3 次
     */
    private String invokeAiWithRetry(String systemPrompt, String userPrompt) throws InterruptedException {
        int maxRetries = 3;
        int retryCount = 0;
        while (true) {
            try {
                return chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
            } catch (org.springframework.web.client.ResourceAccessException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    log.error("❌ AI 调用超时，已重试 {} 次，放弃", maxRetries);
                    throw e;
                }
                long waitTime = 2000L * (long) Math.pow(2, retryCount - 1);
                log.warn("⚠️ AI 调用超时，正在进行第 {} 次重试，等待 {} 秒...", retryCount, waitTime / 1000);
                Thread.sleep(waitTime);
            }
        }
    }

}

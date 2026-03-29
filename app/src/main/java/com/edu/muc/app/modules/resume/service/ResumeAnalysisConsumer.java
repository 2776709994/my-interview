package com.edu.muc.app.modules.resume.service;

import com.edu.muc.app.infrastructure.file.MinioFileStorageService;
import com.edu.muc.app.modules.resume.domain.ResumeAnalyses;
import com.edu.muc.app.modules.resume.domain.Resumes;
import com.edu.muc.app.modules.resume.mapper.ResumeAnalysesMapper;
import com.edu.muc.app.modules.resume.mapper.ResumesMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ResumeAnalysisConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ResumesMapper resumesMapper;
    private final ResumeAnalysesMapper analysesMapper;
    private final ChatClient chatClient;
    private final MinioFileStorageService fileStorageService;
    private final ExecutorService executor;  // 从配置注入
    
    private static final String STREAM_KEY = "resume:analysis";
    private static final String GROUP = "resume-analysis-group";
    private static final String CONSUMER = "consumer-1";


    public ResumeAnalysisConsumer(RedisTemplate<String, Object> redisTemplate,
                                  ResumesMapper resumesMapper,
                                  ResumeAnalysesMapper analysesMapper,
                                  MinioFileStorageService fileStorageService,
                                  ChatClient chatClient,
                                  @org.springframework.beans.factory.annotation.Qualifier("resumeAnalysisExecutor") 
                                  ExecutorService executor) {
        this.redisTemplate = redisTemplate;
        this.resumesMapper = resumesMapper;
        this.analysesMapper = analysesMapper;
        this.chatClient = chatClient;
        this.fileStorageService = fileStorageService;
        this.executor = executor;

    }

    @PostConstruct
    public void start() {
        log.info("✅ Redis Stream 消费者已启动，开始监听 resume:analysis");
        // 1. 确保消费者组存在（如果不存在则创建）
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // 组已存在会抛异常，忽略即可
        }

        // 2. 启动独立线程持续监听
        log.info("✅ 启动独立线程持续监听 resume:analysis");
        Thread listenerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream()
                            .read(
                                    Consumer.from(GROUP, CONSUMER),
                                    StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                            );
                    if (messages != null && !messages.isEmpty()) {
                        for (MapRecord<String, Object, Object> record : messages) {
                            String resumeId = (String) record.getValue().get("resumeId");
                            RecordId recordId = record.getId();
                            
                            // 提交到线程池异步处理，处理完成后再 ACK
                            executor.submit(() -> {
                                try {
                                    processResumeAnalysis(Long.parseLong(resumeId));
                                    // 只有处理成功才 ACK
                                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);
                                } catch (Exception e) {
                                    log.error("❌ 分析任务异常，消息将保留在 PEL 中等待重试，resumeId: {}", resumeId, e);
                                }
                            });
                        }
                    }
                } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                    log.warn("⚠️ Redis 连接断开，5秒后重试... {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    log.error("❌ 监听 Redis Stream 异常", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("🛑 Redis Stream 监听线程已退出");
        }, "redis-stream-listener");
        
        // 设置为守护线程，应用关闭时自动退出
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("✅ 线程启动完成，线程名: {}, 是否守护: {}", listenerThread.getName(), listenerThread.isDaemon());
    }

    /**
     * 应用关闭时关闭线程池
     */
    @PreDestroy
    public void shutdown() {
        log.info(" 开始关闭 Redis Stream 消费者...");
        
        // 1. 关闭主线程池
        log.info(" 等待分析任务完成（最多60秒）...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("⚠️ 线程池未能在60秒内完成所有任务，强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.error("❌ 线程池强制关闭失败");
                }
            }
            log.info("✅ 主线程池已关闭");
        } catch (InterruptedException e) {
            log.error("❌ 关闭线程池时被中断", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("✅ Redis Stream 消费者已关闭");
    }

    private void processResumeAnalysis(Long resumeId) {
        log.info("🚀 开始处理简历分析，简历ID: {}", resumeId);
        
        log.info("📝 查询简历信息，简历ID: {}", resumeId);
        Resumes resume = resumesMapper.selectById(resumeId);
        if (resume == null) {
            log.error("❌ 简历不存在，简历ID: {}", resumeId);
            return;
        }
        log.info("✅ 查询到简历信息，文件名: {}", resume.getOriginalFilename());

        try {
            // 更新状态为 PROCESSING
            log.info("🔄 更新简历状态为 PROCESSING");
            resume.setAnalyzeStatus("PROCESSING");
            resumesMapper.updateById(resume);

            // 创建分析记录
            log.info("📋 创建分析记录");
            ResumeAnalyses analysis = new ResumeAnalyses();
            analysis.setResumeId(resumeId);
            analysis.setAnalyzedAt(LocalDateTime.now());
            analysesMapper.insert(analysis);

            // 提取文本
            String text = resume.getResumeText();
            if (text == null || text.isBlank()) {
                log.info("📄 简历文本为空，开始从 MinIO 下载并解析...");
                try {
                    // 参考 JavaGuide 实现：下载为 byte[] 而不是 InputStream
                    log.info("⬇️ 从 MinIO 下载文件，storageKey: {}", resume.getStorageKey());
                    byte[] fileBytes = fileStorageService.downloadAsBytes(resume.getStorageKey());
                    log.info("✅ 文件下载成功，字节数组长度: {} bytes", fileBytes.length);
                    
                    // 使用 CompletableFuture 实现超时控制，避免创建临时线程池
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        log.info("⚙️ 开始 Tika 解析...");
                        try {
                            return parseContent(fileBytes, resume.getOriginalFilename());
                        } catch (Exception e) {
                            throw new RuntimeException("Tika 解析失败", e);
                        }
                    }, executor);  // 复用主线程池
                    
                    // 设置超时时间为 30 秒
                    text = future.get(30, TimeUnit.SECONDS);
                    
                    if (text == null || text.isBlank()) {
                        log.error("❌ Tika 解析结果为空，该 PDF 可能是扫描件（图片格式）");
                        log.error("💡 建议解决方案：");
                        log.error("  1. 使用 Adobe Acrobat 打开 PDF，工具 → 扫描和 OCR → 识别文本");
                        log.error("  2. 或者找到原始 Word 文档，直接另存为 PDF");
                        log.error("  3. 或者手动复制简历文字内容，粘贴到 Word 后另存为 PDF");
                        throw new RuntimeException("PDF 解析结果为空，可能是扫描件（图片格式），无法提取文字。");
                    }
                    
                    // 清洗文本：去除多余空白和换行
                    text = text.replaceAll("\\s+", " ").trim();
                    
                    log.info("✅ Tika 解析成功，文本长度: {}", text.length());
                    
                    // 更新数据库中的简历文本
                    resume.setResumeText(text);
                    resumesMapper.updateById(resume);
                    log.info("💾 简历文本已保存到数据库");
                } catch (java.util.concurrent.TimeoutException e) {
                    log.error("❌ 文件解析超时（30秒），可能是文件过大或格式复杂");
                    Thread.currentThread().interrupt();
                    text = "简历文本提取失败：解析超时";
                    resume.setAnalyzeStatus("FAILED");
                    resume.setAnalyzeError("文本提取失败：解析超时（超过30秒）");
                    resumesMapper.updateById(resume);
                    return;
                } catch (InterruptedException e) {
                    log.error("❌ 文件解析被中断: {}", e.getMessage(), e);
                    Thread.currentThread().interrupt();
                    text = "简历文本提取失败：解析被中断";
                    resume.setAnalyzeStatus("FAILED");
                    resume.setAnalyzeError("文本提取失败：解析被中断");
                    resumesMapper.updateById(resume);
                    return;
                } catch (Exception e) {
                    log.error("❌ 文件解析失败: {}", e.getMessage(), e);
                    text = "简历文本提取失败：" + e.getMessage();
                    resume.setAnalyzeStatus("FAILED");
                    resume.setAnalyzeError("文本提取失败：" + e.getMessage());
                    resumesMapper.updateById(resume);
                    return;
                }
            } else {
                log.info("✅ 使用已有的简历文本，长度: {}", text.length());
            }

            // 使用提示词模板
            log.info("📝 加载提示词模板...");
            PromptTemplate systemTemplate = new PromptTemplate(
                    new DefaultResourceLoader().getResource("classpath:prompts/resume-analysis-system.st")
            );
            PromptTemplate userTemplate = new PromptTemplate(
                    new DefaultResourceLoader().getResource("classpath:prompts/resume-analysis-user.st")
            );

            // 动态注入当前时间，解决大模型时间幻觉问题
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月");
            String currentTime = java.time.LocalDateTime.now().format(formatter);
            
            String systemPrompt = systemTemplate.render(Map.of(
                    "currentTime", currentTime
            ));
            String userPrompt = userTemplate.render(Map.of("resumeText", text));
            
            log.info("✅ 提示词模板加载完成，当前时间: {}, 用户提示词长度: {}", currentTime, userPrompt.length());

            log.info("🤖 开始调用 AI 分析简历，简历ID: {}", resumeId);

            // 调用 AI 进行分析，添加超时重试逻辑
            String aiResponse;
            int maxRetries = 3;  // 增加重试次数到 3 次
            int retryCount = 0;

            while (true) {
                try {
                    log.info("📡 发送 AI 请求...");
                    aiResponse = chatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content();
                    log.info("✅ AI 响应成功，响应长度: {}", aiResponse != null ? aiResponse.length() : 0);
                    break; // 成功则退出循环
                } catch (org.springframework.web.client.ResourceAccessException e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        log.error("❌ AI 调用超时，已重试 {} 次，放弃", maxRetries);
                        throw e; // 超过最大重试次数，抛出异常
                    }
                    // 指数退避策略：2秒、4秒、8秒
                    long waitTime = 2000L * (long) Math.pow(2, retryCount - 1);
                    log.warn("⚠️ AI 调用超时，正在进行第 {} 次重试，等待 {} 秒...", retryCount, waitTime / 1000);
                    Thread.sleep(waitTime);
                } catch (Exception e) {
                    // 其他异常直接抛出，不重试
                    log.error("❌ AI 调用发生异常: {}", e.getMessage(), e);
                    throw e;
                }
            }

            log.info("✅ AI 分析完成，响应长度: {}", aiResponse != null ? aiResponse.length() : 0);

            // 1. 清洗可能的 Markdown 标记
            log.info("🧹 清洗 AI 响应...");
            String jsonStr = aiResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```", "").trim();
            }

            log.info("🔍 解析 JSON...");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonStr);

            // 2. 提取顶层字段
            log.info("📊 提取评分数据...");
            int overallScore = root.has("overallScore") ? root.get("overallScore").asInt() : 0;
            String summary = root.has("summary") ? root.get("summary").asText("") : "";
            JsonNode strengthsNode = root.has("strengths") ? root.get("strengths") : mapper.createArrayNode();
            JsonNode suggestionsNode = root.has("suggestions") ? root.get("suggestions") : mapper.createArrayNode();

            // 3. 提取 scoreDetail
            JsonNode scoreDetail = root.has("scoreDetail") ? root.get("scoreDetail") : mapper.createObjectNode();
            int projectScore = scoreDetail.has("projectScore") ? scoreDetail.get("projectScore").asInt() : 0;
            int skillMatchScore = scoreDetail.has("skillMatchScore") ? scoreDetail.get("skillMatchScore").asInt() : 0;
            int contentScore = scoreDetail.has("contentScore") ? scoreDetail.get("contentScore").asInt() : 0;
            int structureScore = scoreDetail.has("structureScore") ? scoreDetail.get("structureScore").asInt() : 0;
            int expressionScore = scoreDetail.has("expressionScore") ? scoreDetail.get("expressionScore").asInt() : 0;

            // 4. 更新分析记录对象
            log.info("💾 保存分析结果到数据库...");
            analysis.setOverallScore(overallScore);
            analysis.setSkillMatchScore(skillMatchScore);
            analysis.setStructureScore(structureScore);
            analysis.setExpressionScore(expressionScore);
            analysis.setProjectScore(projectScore);
            analysis.setContentScore(contentScore);
            analysis.setSummary(summary);
            analysis.setStrengthsJson(strengthsNode.toString());
            analysis.setSuggestionsJson(suggestionsNode.toString());
            analysis.setAnalyzedAt(LocalDateTime.now());
            analysesMapper.updateById(analysis);
            
            // 更新简历状态和分析次数
            resume.setAnalyzeStatus("COMPLETED");
            resume.setAccessCount((resume.getAccessCount() != null ? resume.getAccessCount() : 0) + 1);
            resume.setLastAccessedAt(new java.util.Date());
            
            log.info("🎉 简历分析完成！");
            log.info("📊 总分: {}", overallScore);
            log.info("📊 各项评分 - 项目: {}, 技能: {}, 内容: {}, 结构: {}, 表达: {}", 
                    projectScore, skillMatchScore, contentScore, structureScore, expressionScore);
            log.info("✅ 简历分析记录更新完成，简历ID: {}", resumeId);
        } catch (Exception e) {
            log.error("❌ 简历分析发生异常，简历ID: {}, 异常信息: {}", resumeId, e.getMessage());
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 500) {
                errorMsg = errorMsg.substring(0, 500);
            }
            resume.setAnalyzeError(errorMsg);
            resume.setAnalyzeStatus("FAILED");
            log.error("❌ 简历分析发生异常，简历ID: {}, 异常信息: {}", resumeId, errorMsg);
        }
        resumesMapper.updateById(resume);
    }
    
    /**
     * 解析文件内容（参考 JavaGuide 专业配置）
     */
    private String parseContent(byte[] fileBytes, String fileName) throws Exception {
        log.info("开始解析文件: {}, 大小: {} bytes", fileName, fileBytes.length);
        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }

        long startTime = System.currentTimeMillis();
        
        try (java.io.InputStream inputStream = new java.io.ByteArrayInputStream(fileBytes)) {
            // 使用 Tika 的简化 API，自动检测并解析
            org.apache.tika.Tika tika = new org.apache.tika.Tika();
            
            // 设置最大文本长度限制（5MB）
            tika.setMaxStringLength(5 * 1024 * 1024);
            
            // 直接解析为文本
            String content = tika.parseToString(inputStream);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("✅ 文件解析成功，耗时: {} ms, 文本长度: {}", elapsed, content != null ? content.length() : 0);
            
            // 去除多余空白
            if (content != null) {
                content = content.replaceAll("\\s+", " ").trim();
            }
            
            return content;
        } catch (Exception e) {
            log.error("❌ Tika 解析失败: {}", e.getMessage(), e);
            throw e;
        }
    }
}
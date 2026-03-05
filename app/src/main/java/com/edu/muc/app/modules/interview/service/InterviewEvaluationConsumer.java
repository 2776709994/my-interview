package com.edu.muc.app.modules.interview.service;

import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.modules.interview.domain.InterviewAnswer;
import com.edu.muc.app.modules.interview.domain.InterviewQuestion;
import com.edu.muc.app.modules.interview.domain.InterviewSession;
import com.edu.muc.app.modules.interview.mapper.InterviewAnswerMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewQuestionMapper;
import com.edu.muc.app.modules.interview.mapper.InterviewSessionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 面试评估异步消费者
 * 监听 Redis Stream: interview:evaluation
 */
@Component
@Slf4j
public class InterviewEvaluationConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final ChatClient chatClient;
    private final ExecutorService executor;
    
    private static final String STREAM_KEY = "interview:evaluation";
    private static final String GROUP = "interview-evaluation-group";
    private static final String CONSUMER = "consumer-1";

    public InterviewEvaluationConsumer(RedisTemplate<String, Object> redisTemplate,
                                      InterviewSessionMapper sessionMapper,
                                      InterviewQuestionMapper questionMapper,
                                      InterviewAnswerMapper answerMapper,
                                      ChatClient chatClient,
                                      @org.springframework.beans.factory.annotation.Qualifier("resumeAnalysisExecutor") 
                                      ExecutorService executor) {
        this.redisTemplate = redisTemplate;
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.chatClient = chatClient;
        this.executor = executor;
    }

    @PostConstruct
    public void start() {
        log.info("✅ Redis Stream 面试评估消费者已启动，开始监听 {}", STREAM_KEY);
        
        // 1. 确保消费者组存在
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // 组已存在会抛异常，忽略即可
        }

        // 2. 启动独立线程持续监听
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
                            String sessionId = (String) record.getValue().get("sessionId");
                            
                            // 提交到线程池异步处理
                            executor.submit(() -> {
                                try {
                                    processInterviewEvaluation(sessionId);
                                } catch (Exception e) {
                                    log.error("❌ 面试评估任务异常, sessionId: {}", sessionId, e);
                                }
                            });
                            
                            // 确认消息处理完成
                            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                            
                            // 自动清理：最多保留 100 条消息
                            redisTemplate.opsForStream().trim(STREAM_KEY, 100, false);
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
            log.info("🛑 面试评估监听线程已退出");
        }, "interview-evaluation-listener");
        
        // 设置为守护线程
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("✅ 面试评估监听线程已启动: {}, 守护: {}", listenerThread.getName(), listenerThread.isDaemon());
    }

    /**
     * 应用关闭时优雅关闭
     */
    @PreDestroy
    public void shutdown() {
        log.info("🛑 开始关闭面试评估消费者...");
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("⚠️ 线程池未能在60秒内完成所有任务，强制关闭");
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("❌ 线程池强制关闭失败");
                }
            }
            log.info("✅ 面试评估线程池已关闭");
        } catch (InterruptedException e) {
            log.error("❌ 关闭线程池时被中断", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理面试评估
     */
    private void processInterviewEvaluation(String sessionId) {
        log.info("🚀 开始处理面试评估, sessionId: {}", sessionId);
        
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            log.error("❌ 面试会话不存在, sessionId: {}", sessionId);
            return;
        }

        try {
            // 更新状态为 PROCESSING
            session.setEvaluateStatus("PROCESSING");
            sessionMapper.updateById(session);
            log.info("🔄 评估状态已更新为 PROCESSING");

            // 1. 获取所有问题和答案
            List<InterviewQuestion> questions = questionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewQuestion>()
                            .eq(InterviewQuestion::getSessionId, sessionId)
                            .orderByAsc(InterviewQuestion::getQuestionIndex)
            );
            
            List<InterviewAnswer> answers = answerMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewAnswer>()
                            .eq(InterviewAnswer::getSessionId, sessionId)
                            .orderByAsc(InterviewAnswer::getQuestionIndex)
            );
            
            // 详细日志：记录查询结果
            log.info("📊 查询结果 - 问题数: {}, 答案数: {}", questions.size(), answers.size());
            
            if (questions.isEmpty()) {
                log.error("❌ 会话 {} 没有面试问题记录", sessionId);
                throw new BusinessException("NO_QUESTIONS_FOUND", "会话没有面试问题，无法评估");
            }
            
            // 处理白卷情况：生成零分评估报告
            if (answers.isEmpty()) {
                log.warn("⚠️ 会话 {} 用户未回答任何问题，生成零分评估报告", sessionId);
                generateEmptyEvaluation(session, questions);
                return;  // 直接返回，不执行后续 AI 评估
            }

            // 2. 构建评估请求
            StringBuilder evaluationPrompt = new StringBuilder();
            evaluationPrompt.append("请对以下面试回答进行评估：\n\n");
            
            for (int i = 0; i < questions.size(); i++) {
                InterviewQuestion q = questions.get(i);
                InterviewAnswer a = answers.stream()
                        .filter(ans -> ans.getQuestionIndex().equals(q.getQuestionIndex()))
                        .findFirst()
                        .orElse(null);
                
                if (a != null) {
                    evaluationPrompt.append(String.format(
                            "问题 %d：%s\n类别：%s\n回答：%s\n\n",
                            i + 1, q.getQuestion(), q.getCategory(), a.getAnswer()
                    ));
                }
            }
            
            evaluationPrompt.append("\n请输出 JSON 格式，包含：");
            evaluationPrompt.append("overallScore（总分0-100）, overallFeedback, strengths（数组）, improvements（数组）, ");
            evaluationPrompt.append("questionEvaluations（数组，每项包含questionIndex, score, feedback）");

            // 3. 调用 AI 评估
            log.info("🤖 调用 AI 进行评估...");
            String aiResponse = chatClient.prompt()
                    .system("你是一位资深技术面试官，请客观、专业地评估候选人的回答。")
                    .user(evaluationPrompt.toString())
                    .call()
                    .content();
            
            log.info("✅ AI 评估完成，响应长度: {}", aiResponse.length());

            // 4. 解析评估结果
            ObjectMapper objectMapper = new ObjectMapper();
            String jsonStr = extractJson(aiResponse);
            Map<String, Object> result = objectMapper.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
            
            // 5. 更新会话
            session.setOverallScore((Integer) result.getOrDefault("overallScore", 0));
            session.setOverallFeedback((String) result.get("overallFeedback"));
            
            @SuppressWarnings("unchecked")
            List<String> strengths = (List<String>) result.get("strengths");
            session.setStrengthsJson(objectMapper.writeValueAsString(strengths != null ? strengths : List.of()));
            
            @SuppressWarnings("unchecked")
            List<String> improvements = (List<String>) result.get("improvements");
            session.setImprovementsJson(objectMapper.writeValueAsString(improvements != null ? improvements : List.of()));
            
            // 6. 更新每个问题的评分
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questionEvaluations = (List<Map<String, Object>>) result.get("questionEvaluations");
            
            if (questionEvaluations != null) {
                for (Map<String, Object> eval : questionEvaluations) {
                    int qIndex = (Integer) eval.get("questionIndex");
                    double score = ((Number) eval.get("score")).doubleValue();
                    String feedback = (String) eval.get("feedback");
                    
                    // 更新答案表
                    InterviewAnswer answer = answerMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewAnswer>()
                                    .eq(InterviewAnswer::getSessionId, sessionId)
                                    .eq(InterviewAnswer::getQuestionIndex, qIndex)
                    );
                    
                    if (answer != null) {
                        answer.setScore((int) score);
                        answer.setFeedback(feedback);
                        answer.setEvaluatedAt(LocalDateTime.now());
                        answerMapper.updateById(answer);
                    }
                }
            }
            
            session.setEvaluateStatus("COMPLETED");
            session.setStatus("EVALUATED");
            session.setEvaluatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            
            log.info("✅ 评估报告生成完成, sessionId: {}", sessionId);

        } catch (Exception e) {
            log.error("❌ 生成评估报告失败, sessionId: {}", sessionId, e);
            session.setEvaluateStatus("FAILED");
            session.setEvaluateError(e.getMessage());
            sessionMapper.updateById(session);
        }
    }

    /**
     * 从 AI 响应中提取 JSON
     */
    private String extractJson(String response) {
        if (response == null) return "{}";
        
        // 去除 Markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```", "").trim();
        }
        
        // 尝试找到第一个 { 和最后一个 }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        
        return cleaned;
    }

    /**
     * 生成白卷评估报告（零分）
     */
    private void generateEmptyEvaluation(InterviewSession session, List<InterviewQuestion> questions) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            
            // 设置零分
            session.setOverallScore(0);
            session.setOverallFeedback("候选人未回答任何问题，无法进行有效评估。建议认真准备面试，积极回答问题以展示自己的能力。");
            
            // 设置优点为空
            session.setStrengthsJson(objectMapper.writeValueAsString(List.of()));
            
            // 设置改进建议
            List<String> improvements = List.of(
                "面试中应积极回答问题，即使不确定也要尝试表达自己的思路",
                "建议提前准备常见面试题，增强自信心",
                "遇到不会的问题可以请求提示或换个角度思考",
                "保持冷静，不要因为紧张而放弃回答"
            );
            session.setImprovementsJson(objectMapper.writeValueAsString(improvements));
            
            // 为所有问题创建零分答案记录
            for (InterviewQuestion question : questions) {
                InterviewAnswer answer = new InterviewAnswer();
                answer.setSessionId(session.getSessionId());
                answer.setQuestionIndex(question.getQuestionIndex());
                answer.setAnswer("");
                answer.setScore(0);
                answer.setFeedback("未作答");
                answer.setCreatedAt(LocalDateTime.now());
                answer.setEvaluatedAt(LocalDateTime.now());
                answerMapper.insert(answer);
            }
            
            // 更新会话状态
            session.setEvaluateStatus("COMPLETED");
            session.setStatus("EVALUATED");
            session.setEvaluatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            
            log.info("✅ 白卷评估报告生成完成, sessionId: {}, 总分: 0", session.getSessionId());
            
        } catch (Exception e) {
            log.error("❌ 生成白卷评估报告失败", e);
            throw new BusinessException("EMPTY_EVALUATION_FAILED", "生成白卷评估报告失败: " + e.getMessage());
        }
    }
}

package com.edu.muc.app;

import com.edu.muc.app.common.ai.SseTextStream;
import com.edu.muc.app.common.async.AbstractStreamConsumer;
import com.edu.muc.app.common.exception.BusinessException;
import com.edu.muc.app.infrastructure.redis.StreamPendingRecoverer;
import com.edu.muc.app.modules.interview.dto.SubmitAnswerRequest;
import com.edu.muc.app.modules.interview.service.InterviewService;
import com.edu.muc.app.modules.knowledgebase.service.SmartRetrievalService;
import com.edu.muc.app.modules.voiceinterview.service.QwenTtsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AppApplicationTests.SseFixture.class)
class AppApplicationTests {

    @MockitoBean
    private QwenTtsService ttsService;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InterviewService interviews;

    @Autowired
    private SmartRetrievalService retrieval;

    @Autowired
    private RedisTemplate<String, Object> redis;

    @Autowired
    private StreamPendingRecoverer pendingRecoverer;

    @Test
    void contextLoads() {
    }

    @Test
    void sendsNamedSseEventsWithJsonEncodedMarkdown() {
        byte[] bytes = RestClient.create("http://127.0.0.1:" + port)
                .get().uri("/audit-test/sse")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve().body(byte[].class);
        assertNotNull(bytes);
        String output = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(output.contains("event:chunk"));
        assertTrue(output.contains("第一行\\n    第二行"));
        assertTrue(output.contains("event:done"));
    }

    @RestController
    static class SseFixture {
        @GetMapping(value = "/audit-test/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            SseTextStream stream = new SseTextStream();
            return stream.start(Runnable::run,
                    () -> stream.send(Flux.just("第一行\n    第二行"), () -> {}));
        }
    }

    @Test
    @Transactional
    void onlyCurrentQuestionCanBeAnsweredAndCompletedSessionsAreReadOnly() {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO interview_sessions (session_id, status, current_question_index, total_questions)
                VALUES (?, 'IN_PROGRESS', 0, 2)
                """, sessionId);
        jdbc.update("""
                INSERT INTO interview_questions (session_id, question_index, question, category)
                VALUES (?, 0, '第一题', '技术'), (?, 1, '第二题', '技术')
                """, sessionId, sessionId);

        SubmitAnswerRequest answer = new SubmitAnswerRequest();
        answer.setSessionId(sessionId);
        answer.setAnswer("答复");
        answer.setQuestionIndex(1);
        assertThrows(BusinessException.class, () -> interviews.submitAnswer(answer));
        assertThrows(BusinessException.class, () -> interviews.getReport(sessionId));

        answer.setQuestionIndex(0);
        assertEquals(1, interviews.submitAnswer(answer).getCurrentIndex());
        assertThrows(BusinessException.class, () -> interviews.saveAnswer(answer));
        answer.setQuestionIndex(1);
        assertFalse(interviews.submitAnswer(answer).isHasNextQuestion());
        assertThrows(BusinessException.class, () -> interviews.saveAnswer(answer));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM interview_answers WHERE session_id = ?", Integer.class, sessionId));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT evaluate_status FROM interview_sessions WHERE session_id = ?", String.class, sessionId));
    }

    @Test
    @Transactional
    void retrievalUsesPgvectorAndOnlyReturnsCompletedParents() {
        String vector = "[1," + String.join(",", Collections.nCopies(1023, "0")) + "]";
        Long complete = jdbc.queryForObject("""
                INSERT INTO knowledge_documents (name, file_name, vector_status)
                VALUES ('audit', 'audit.txt', 'COMPLETED') RETURNING id
                """, Long.class);
        Long processing = jdbc.queryForObject("""
                INSERT INTO knowledge_documents (name, file_name, vector_status)
                VALUES ('audit', 'audit.txt', 'PROCESSING') RETURNING id
                """, Long.class);
        for (Long id : List.of(complete, processing)) {
            jdbc.update("""
                    INSERT INTO knowledge_documents (name, file_name, content, parent_id, content_embedding, vector_status)
                    VALUES ('audit chunk', 'audit.txt', 'Redis Streams support consumer groups', ?, CAST(? AS vector), 'COMPLETED')
                    """, id, vector);
        }
        assertEquals(List.of(complete), retrieval.smartRetrieve(vector, List.of(complete, processing)).stream()
                .map(document -> document.getParentId()).toList());
    }

    @Test
    void consumesRecordsPublishedBeforeGroupCreationAndRetriesThreeTimes() {
        String key = "talentpilot:audit:" + UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        AbstractStreamConsumer<String> consumer = new AbstractStreamConsumer<>(redis, pendingRecoverer) {
            @Override protected ExecutorService executor() { return workers; }
            @Override protected String taskDisplayName() { return "audit-test"; }
            @Override protected String streamKey() { return key; }
            @Override protected String groupName() { return "audit"; }
            @Override protected String consumerPrefix() { return "audit-"; }
            @Override protected String threadName() { return "audit-consumer"; }
            @Override protected Map<String, String> buildMessage(String payload) { return Map.of("payload", payload); }
            @Override protected String parsePayload(MapRecord<String, Object, Object> record) {
                return (String) record.getValue().get("payload");
            }
            @Override protected String payloadIdentifier(String payload) { return payload; }
            @Override protected boolean tryMarkProcessing(String payload) { return true; }
            @Override protected void processBusiness(String payload) {
                if (attempts.incrementAndGet() <= 3) {
                    throw new IllegalStateException("模拟可重试故障");
                }
            }
            @Override protected void markCompleted(String payload) { completed.set(true); }
            @Override protected void markFailed(String payload, String error) { failed.set(true); }
        };
        redis.opsForStream().add(StreamRecords.newRecord().in(key).ofMap(Map.of("payload", "prequeued")));
        consumer.init();
        try {
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> completed.get() && Long.valueOf(0).equals(redis.opsForStream().size(key)));
            assertEquals(4, attempts.get());
            assertFalse(failed.get());
        } finally {
            consumer.shutdown();
        }
    }
}

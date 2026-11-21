package com.edu.muc.app.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
public final class SseTextStream {
    private final SseEmitter emitter = new SseEmitter(180_000L);
    private final Disposable.Swap subscription = Disposables.swap();

    public SseTextStream() {
        emitter.onCompletion(subscription::dispose);
        emitter.onError(ignored -> subscription.dispose());
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.complete();
        });
    }

    public SseEmitter start(Executor executor, Runnable setup) {
        executor.execute(() -> {
            try {
                if (!subscription.isDisposed()) {
                    setup.run();
                }
            } catch (Exception error) {
                fail(error);
            }
        });
        return emitter;
    }

    public void send(Flux<String> chunks, Runnable saveResult) {
        if (subscription.isDisposed()) {
            return;
        }
        subscription.update(chunks
                .publishOn(Schedulers.boundedElastic(), 16)
                .doOnNext(chunk -> emit("chunk", Map.of("text", chunk)))
                .doOnComplete(() -> {
                    saveResult.run();
                    emit("done", Map.of());
                    emitter.complete();
                })
                .subscribe(ignored -> {}, this::fail));
    }

    private void emit(String event, Map<String, String> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void fail(Throwable error) {
        log.error("知识库问答流中断", error);
        try {
            if (!(error instanceof UncheckedIOException) && !subscription.isDisposed()) {
                emit("error", Map.of("message", "生成回答失败，请稍后重试"));
            }
        } catch (IllegalStateException | UncheckedIOException sendError) {
            log.debug("问答连接已关闭", sendError);
        } finally {
            emitter.complete();
        }
    }
}

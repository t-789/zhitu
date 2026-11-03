package org.example.lifecomposer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated executor for SSE streaming so the request thread returns immediately.
 *
 * v0.1.2: bounded. At most {@code app.security.chat-stream-max-concurrent} streams
 * run at once; there is no queue, so an extra stream is rejected immediately
 * (RejectedExecutionException) and ChatController answers 503 CHAT_SERVER_BUSY.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor(AppSecurityProperties properties) {
        int max = Math.max(1, properties.getChatStreamMaxConcurrent());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                max, max, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-stream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}

package org.example.lifecomposer.Service;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.lifecomposer.config.LlmConfig.UseCaseConfig;
import org.example.lifecomposer.dto.LlmRequestDto;
import org.example.lifecomposer.dto.LlmResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1-14: retry only before any body byte was consumed; never replay a started stream. */
class OpenAiCompatibleLlmClientRetryTest {

    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}";

    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private UseCaseConfig config;

    @BeforeEach
    void setUp() {
        config = new UseCaseConfig();
        config.setProvider("deepseek");
        config.setBaseUrl("http://llm.test");
        config.setModel("m");
        config.setEnabled(true);
        config.setMaxRetries(2);
        config.setRetryBaseDelayMillis(100);
    }

    /** Each script entry is an HTTP status, or a negative number for an IOException kind. */
    private OpenAiCompatibleLlmClient client(Step... script) {
        Interceptor interceptor = chain -> {
            Step step = script[Math.min(calls.getAndIncrement(), script.length - 1)];
            return step.respond(chain);
        };
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new OpenAiCompatibleLlmClient(config, http, "test-key", sleeps::add);
    }

    private LlmResponseDto chat(OpenAiCompatibleLlmClient client) {
        LlmRequestDto request = new LlmRequestDto();
        request.setMessage("hello");
        request.setUseCase("chat");
        return client.chat(request);
    }

    @Test
    void retriesServiceUnavailableThenSucceeds() {
        LlmResponseDto response = chat(client(Step.status(503), Step.status(200)));

        assertFalse(response.isFailed());
        assertEquals("hi", response.getContent());
        assertEquals(2, calls.get());
        assertEquals(List.of(100L), sleeps);
    }

    @Test
    void backsOffExponentiallyAndGivesUpAfterMaxRetries() {
        LlmResponseDto response = chat(client(Step.status(429)));

        assertTrue(response.isFailed());
        assertEquals(3, calls.get(), "1 次原始请求 + 2 次重试");
        assertEquals(List.of(100L, 200L), sleeps);
    }

    @Test
    void honoursRetryAfterButCapsIt() {
        LlmResponseDto response = chat(client(Step.statusWithRetryAfter(429, "2"), Step.statusWithRetryAfter(429, "999"),
                Step.status(200)));

        assertFalse(response.isFailed());
        assertEquals(List.of(2000L, 5000L), sleeps);
    }

    @Test
    void doesNotRetryClientErrors() {
        LlmResponseDto response = chat(client(Step.status(400)));

        assertTrue(response.isFailed());
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesConnectionFailures() {
        LlmResponseDto response = chat(client(Step.io(new ConnectException("refused")), Step.status(200)));

        assertFalse(response.isFailed());
        assertEquals(2, calls.get());
    }

    @Test
    void retriesConnectTimeoutButNotReadTimeout() {
        assertFalse(chat(client(Step.io(new SocketTimeoutException("connect timed out")), Step.status(200))).isFailed());

        calls.set(0);
        sleeps.clear();
        LlmResponseDto readTimeout = chat(client(Step.io(new SocketTimeoutException("timeout"))));
        assertTrue(readTimeout.isFailed());
        assertEquals(1, calls.get(), "读超时可能模型仍在生成，不重试");
    }

    @Test
    void zeroRetriesKeepsOldBehaviour() {
        config.setMaxRetries(0);
        LlmResponseDto response = chat(client(Step.status(503), Step.status(200)));

        assertTrue(response.isFailed());
        assertEquals(1, calls.get());
    }

    @Test
    void streamRetriesBeforeAnyTokenButNeverAfterItStarted() {
        // 503 on the first attempt: nothing was streamed, so it is safe to retry.
        RecordingListener retried = new RecordingListener();
        client(Step.status(503), Step.sse("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"))
                .chatStream(request(), retried);
        assertEquals(2, calls.get());
        assertEquals(List.of("ok"), retried.tokens);
        assertEquals(0, retried.errors);

        // A stream that already delivered a token and then breaks must not be replayed.
        calls.set(0);
        sleeps.clear();
        RecordingListener broken = new RecordingListener();
        client(Step.sse("data: {\"choices\":[{\"delta\":{\"content\":\"par\"}}]}\n\n"), Step.status(200))
                .chatStream(request(), broken);
        assertEquals(1, calls.get(), "流已开始后不得重试，否则会重复输出");
        assertTrue(sleeps.isEmpty());
    }

    private LlmRequestDto request() {
        LlmRequestDto request = new LlmRequestDto();
        request.setMessage("hello");
        request.setUseCase("chat");
        return request;
    }

    private static final class RecordingListener implements LlmStreamListener {
        final List<String> tokens = new ArrayList<>();
        int errors;

        @Override
        public void onContentDelta(String delta) {
            tokens.add(delta);
        }

        @Override
        public void onComplete(LlmResponseDto response) {
        }

        @Override
        public void onError(LlmResponseDto response) {
            errors++;
        }
    }

    @FunctionalInterface
    private interface Step {
        Response respond(Interceptor.Chain chain) throws IOException;

        static Step status(int code) {
            return chain -> build(chain, code, code == 200 ? OK_BODY : "{\"error\":\"x\"}", null, "application/json");
        }

        static Step statusWithRetryAfter(int code, String retryAfter) {
            return chain -> build(chain, code, "{}", retryAfter, "application/json");
        }

        static Step sse(String body) {
            return chain -> build(chain, 200, body, null, "text/event-stream");
        }

        static Step io(IOException e) {
            return chain -> {
                throw e;
            };
        }

        private static Response build(Interceptor.Chain chain, int code, String body, String retryAfter, String type) {
            Response.Builder builder = new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("m")
                    .body(ResponseBody.create(body, MediaType.parse(type)));
            if (retryAfter != null) {
                builder.header("Retry-After", retryAfter);
            }
            return builder.build();
        }
    }
}

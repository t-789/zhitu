package org.example.lifecomposer.Controller;

import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Entity.ChatMessage;
import org.example.lifecomposer.Entity.User;
import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.Service.ChatConcurrencyGuard;
import org.example.lifecomposer.Service.ChatQuotaDecision;
import org.example.lifecomposer.Service.ChatQuotaService;
import org.example.lifecomposer.Service.ChatService;
import org.example.lifecomposer.agent.AgentEventListener;
import org.example.lifecomposer.agent.LlmUnavailableException;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.example.lifecomposer.dto.ChatRequest;
import org.example.lifecomposer.dto.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger LOG = LogManager.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatService chatService;
    private final UserRepository userRepository;
    private final ExecutorService chatStreamExecutor;
    private final ChatQuotaService chatQuotaService;
    private final AppSecurityProperties securityProperties;
    private final ChatConcurrencyGuard concurrencyGuard;

    public ChatController(ChatService chatService,
                          UserRepository userRepository,
                          @Qualifier("chatStreamExecutor") ExecutorService chatStreamExecutor,
                          ChatQuotaService chatQuotaService,
                          AppSecurityProperties securityProperties,
                          ChatConcurrencyGuard concurrencyGuard) {
        this.chatService = chatService;
        this.userRepository = userRepository;
        this.chatStreamExecutor = chatStreamExecutor;
        this.chatQuotaService = chatQuotaService;
        this.securityProperties = securityProperties;
        this.concurrencyGuard = concurrencyGuard;
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@Valid @RequestBody ChatRequest request,
                                         Authentication authentication) {
        Integer userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        // v0.1.2: take the per-user slot before consuming quota, so a rejected
        // concurrent request costs nothing.
        if (!concurrencyGuard.tryAcquire(userId)) {
            return inProgressResponse();
        }
        try {
            ChatQuotaDecision quota = chatQuotaService.tryConsume(userId);
            if (!quota.allowed()) {
                return quotaResponse(quota);
            }
            ChatResponse response = chatService.sendMessage(
                    userId, request.getMessage(), effectiveMaxTokens(request.getMaxTokens()));
            return ResponseEntity.ok(response);
        } catch (LlmUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", e.getCode(),
                    "message", "LLM 不可用，请稍后重试"));
        } finally {
            concurrencyGuard.release(userId);
        }
    }

    @PostMapping("/stream")
    public ResponseEntity<?> streamMessage(@Valid @RequestBody ChatRequest request,
                                           Authentication authentication) {
        Integer userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        if (!concurrencyGuard.tryAcquire(userId)) {
            return inProgressResponse();
        }
        // From here the slot is released by exactly one owner: this thread on
        // every early return, otherwise the stream task's finally block.
        ChatQuotaDecision quota;
        try {
            quota = chatQuotaService.tryConsume(userId);
        } catch (RuntimeException e) {
            concurrencyGuard.release(userId);
            throw e;
        }
        if (!quota.allowed()) {
            concurrencyGuard.release(userId);
            return quotaResponse(quota);
        }

        int maxTokens = effectiveMaxTokens(request.getMaxTokens());
        SseEmitter emitter = new SseEmitter(securityProperties.getSseTimeoutMillis());
        try {
            chatStreamExecutor.execute(() -> runStream(userId, request, maxTokens, emitter));
        } catch (RejectedExecutionException e) {
            // v0.1.2: bounded executor is full. The request never reached the
            // model, so give back the quota as well as the slot.
            concurrencyGuard.release(userId);
            chatQuotaService.refund(userId);
            LOG.warn("Chat stream rejected for user {}: executor saturated", userId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "CHAT_SERVER_BUSY",
                    "message", "当前对话人数较多，请稍后重试"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void runStream(Integer userId, ChatRequest request, int maxTokens, SseEmitter emitter) {
        // The slot is released once, before the terminal event is sent, so a
        // client that starts its next turn as soon as it sees "done"/"error" is
        // never refused with CHAT_IN_PROGRESS. The flag stops a second release
        // from freeing a slot that a newer request already holds.
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseSlot = () -> {
            if (released.compareAndSet(false, true)) {
                concurrencyGuard.release(userId);
            }
        };
        try {
            SseAgentEventListener listener = new SseAgentEventListener(emitter);
            try {
                chatService.streamMessage(userId, request.getMessage(), listener, maxTokens);
                releaseSlot.run();
                send(emitter, "done", Map.of());
                emitter.complete();
            } catch (ClientDisconnectedException e) {
                LOG.info("SSE client disconnected during chat stream for user {}", userId);
                releaseSlot.run();
                emitter.completeWithError(e);
            } catch (LlmUnavailableException e) {
                releaseSlot.run();
                completeWithErrorEvent(emitter, e.getCode(), "LLM 不可用，请稍后重试");
            } catch (Exception e) {
                LOG.error("Chat stream failed for user {}: {}", userId, e.getMessage(), e);
                releaseSlot.run();
                completeWithErrorEvent(emitter, "INTERNAL_ERROR", "服务异常，请稍后重试");
            }
        } finally {
            releaseSlot.run();
        }
    }

    private ResponseEntity<?> inProgressResponse() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CHAT_IN_PROGRESS",
                "message", "上一条消息还在处理中，请稍候"));
    }

    private ResponseEntity<?> quotaResponse(ChatQuotaDecision quota) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", quota.error());
        body.put("message", quota.message());
        body.put("retryAfterSeconds", quota.retryAfterSeconds());
        body.put("dailyLimit", quota.dailyLimit());
        body.put("usedToday", quota.usedToday());
        body.put("remainingToday", quota.remainingToday());
        body.put("minuteLimit", quota.minuteLimit());
        body.put("minuteRemaining", quota.minuteRemaining());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(quota.retryAfterSeconds()))
                .body(body);
    }

    private int effectiveMaxTokens(Integer requestedMaxTokens) {
        int limit = securityProperties.getChatMaxOutputTokens();
        if (requestedMaxTokens == null) {
            return limit;
        }
        return Math.max(1, Math.min(requestedMaxTokens, limit));
    }

    private void completeWithErrorEvent(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", Map.of("code", code, "message", message));
            send(emitter, "done", Map.of());
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(Authentication authentication) {
        Integer userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        List<ChatMessage> messages = chatService.getHistory(userId);
        return ResponseEntity.ok(messages);
    }

    @DeleteMapping("/context")
    public ResponseEntity<?> clearContext(Authentication authentication) {
        Integer userId = resolveUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        int deleted = chatService.clearContext(userId);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private Integer resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        User user = userRepository.findByUsername(authentication.getName());
        return user != null ? user.getId() : null;
    }

    /** Forwards agent lifecycle events as SSE named events. */
    private final class SseAgentEventListener implements AgentEventListener {

        private final SseEmitter emitter;

        private SseAgentEventListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onPromptVersions(java.util.Map<String, String> versions) {
            send(emitter, "prompt_versions", Map.of("versions", versions));
        }

        @Override
        public void onThinkingStart(Instant startedAt) {
            send(emitter, "thinking_start", Map.of("startedAt", startedAt.toString()));
        }

        @Override
        public void onThinkingTick(long elapsedSeconds) {
            send(emitter, "thinking_tick", Map.of("elapsedSeconds", elapsedSeconds));
        }

        @Override
        public void onThinkingEnd(long elapsedMs) {
            send(emitter, "thinking_end", Map.of("elapsedMs", elapsedMs));
        }

        @Override
        public void onToolCall(String callId, String name, String description, String argumentsJson) {
            send(emitter, "tool_call", Map.of(
                    "callId", callId,
                    "name", name,
                    "description", description,
                    "arguments", argumentsJson));
        }

        @Override
        public void onToolResult(String callId, String name, boolean ok, String resultJson) {
            send(emitter, "tool_result", Map.of(
                    "callId", callId,
                    "name", name,
                    "ok", ok,
                    "result", resultJson));
        }

        @Override
        public void onToken(String delta) {
            send(emitter, "token", Map.of("delta", delta));
        }

        @Override
        public void onAssistantMessage(String content, String createTime) {
            send(emitter, "assistant_message", Map.of(
                    "content", content,
                    "createTime", createTime));
        }

        @Override
        public void onProfileChangeProposal(String proposalsJson) {
            send(emitter, "profile_change_proposal", Map.of("proposals", parseJson(proposalsJson)));
        }

        @Override
        public void onAwaitingConfirmation(String proposalsJson) {
            send(emitter, "awaiting_confirmation", Map.of(
                    "status", "awaiting_confirmation",
                    "proposals", parseJson(proposalsJson)));
        }

        @Override
        public void onError(String code, String message) {
            send(emitter, "error", Map.of("code", code, "message", message));
        }

        @Override
        public void onDone() {
            send(emitter, "done", Map.of());
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    /** Raised when the browser closes the SSE connection mid-stream. */
    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(Throwable cause) {
            super("SSE client disconnected", cause);
        }
    }
}

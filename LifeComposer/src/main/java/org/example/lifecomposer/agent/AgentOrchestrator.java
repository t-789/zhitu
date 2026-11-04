package org.example.lifecomposer.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.Entity.ChatMessage;
import org.example.lifecomposer.Repository.ChatMessageRepository;
import org.example.lifecomposer.Service.FallbackLlmClient;
import org.example.lifecomposer.Service.LlmClient;
import org.example.lifecomposer.Service.LlmClientFactory;
import org.example.lifecomposer.Service.LlmStreamListener;
import org.example.lifecomposer.Service.PromptCatalog;
import org.example.lifecomposer.Service.PromptComposer;
import org.example.lifecomposer.Service.PromptId;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.example.lifecomposer.dto.ChatResponse;
import org.example.lifecomposer.dto.LlmChatMessage;
import org.example.lifecomposer.dto.LlmRequestDto;
import org.example.lifecomposer.dto.LlmResponseDto;
import org.example.lifecomposer.dto.LlmToolCall;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-round tool-use agent orchestration for /api/chat/*.
 *
 * Persistence rules (v0.0.4):
 * - user message is written first;
 * - each assistant tool_call and each tool result are written as separate rows;
 * - final assistant text is written only after a complete model answer;
 * - thinking/tick/timer state and partial text are never persisted.
 */
@Service
public class AgentOrchestrator {

    private static final Logger LOG = LogManager.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final java.util.Set<String> RECOMMENDATION_TOOLS = java.util.Set.of(
            "list_growth_directions", "get_capability_gap", "get_recommendation_reasons", "get_path_plan");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    /** JVM default zone is UTC (storage); the display strings keep campus local time. */
    private static final java.time.ZoneId DISPLAY_ZONE = java.time.ZoneId.of("Asia/Shanghai");


    private final ChatMessageRepository chatMessageRepository;
    private final LlmClientFactory llmClientFactory;
    private final ToolRegistry toolRegistry;
    private final AppSecurityProperties securityProperties;
    private final PromptCatalog promptCatalog;
    private final PromptComposer promptComposer;

    /** Test/legacy constructor; uses an in-memory copy of the classpath prompts. */
    public AgentOrchestrator(ChatMessageRepository chatMessageRepository,
                             LlmClientFactory llmClientFactory,
                             ToolRegistry toolRegistry,
                             AppSecurityProperties securityProperties) {
        this(chatMessageRepository, llmClientFactory, toolRegistry, securityProperties, new PromptCatalog());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentOrchestrator(ChatMessageRepository chatMessageRepository,
                             LlmClientFactory llmClientFactory,
                             ToolRegistry toolRegistry,
                             AppSecurityProperties securityProperties,
                             PromptCatalog promptCatalog) {
        this.chatMessageRepository = chatMessageRepository;
        this.llmClientFactory = llmClientFactory;
        this.toolRegistry = toolRegistry;
        this.securityProperties = securityProperties;
        this.promptCatalog = promptCatalog;
        this.promptComposer = new PromptComposer(promptCatalog);
    }

    /** Legacy /api/chat/send path: runs the same agent loop without SSE. */
    public ChatResponse sendMessage(Integer userId, String userMessage) {
        return sendMessage(userId, userMessage, null);
    }

    public ChatResponse sendMessage(Integer userId, String userMessage, Integer requestedMaxTokens) {
        PromptComposer.ComposedPrompt prompt = promptComposer.forUserMessage(userMessage);
        List<LlmChatMessage> conversation = prepareConversation(userId, userMessage, prompt);
        java.util.Map<String, String> usedVersions = new LinkedHashMap<>();
        String content = runLoop(userId, conversation, NoopAgentEventListener.INSTANCE, false,
                requestedMaxTokens, prompt, usedVersions);
        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        if (content == null) {
            response.setAwaitingConfirmation(true);
            response.setContent("我整理出了一些画像变更建议，请在确认卡片中确认或拒绝。");
        } else {
            response.setAwaitingConfirmation(false);
            response.setContent(content);
        }
        response.setPromptVersions(usedVersions);
        response.setCreateTime(LocalDateTime.now(DISPLAY_ZONE).format(TIME_FORMATTER));
        response.setMocked(false);
        response.setError(null);
        return response;
    }

    /**
     * Server-initiated continuation after a profile-change decision. The decision
     * context is delivered as a transient user turn (not persisted as a new user
     * message); the candidate table already stores the durable decision.
     */
    public ChatResponse continueAfterProfileDecision(Integer userId, String decisionContext) {
        return continueAfterProfileDecision(userId, decisionContext, null);
    }

    public ChatResponse continueAfterProfileDecision(Integer userId, String decisionContext,
                                                     Integer requestedMaxTokens) {
        PromptComposer.ComposedPrompt prompt = promptComposer.forDecisionContinuation();
        List<LlmChatMessage> messages = new ArrayList<>();
        messages.add(LlmChatMessage.system(prompt.systemPrompt()));
        messages.addAll(toLlmMessages(recentContext(userId)));
        messages.add(LlmChatMessage.user(decisionContext));
        java.util.Map<String, String> usedVersions = new LinkedHashMap<>();
        String content = runLoop(userId, messages, NoopAgentEventListener.INSTANCE, false,
                requestedMaxTokens, prompt, usedVersions);
        ChatResponse response = new ChatResponse();
        response.setRole("assistant");
        if (content == null) {
            response.setAwaitingConfirmation(true);
            response.setContent("已根据你的决定更新上下文；还有新的画像变更候选等待确认。");
        } else {
            response.setAwaitingConfirmation(false);
            response.setContent(content);
        }
        response.setPromptVersions(usedVersions);
        response.setCreateTime(LocalDateTime.now(DISPLAY_ZONE).format(TIME_FORMATTER));
        response.setMocked(false);
        response.setError(null);
        return response;
    }

    /** Streaming /api/chat/stream path. */
    public void streamMessage(Integer userId, String userMessage, AgentEventListener listener) {
        streamMessage(userId, userMessage, listener, null);
    }

    public void streamMessage(Integer userId, String userMessage, AgentEventListener listener,
                              Integer requestedMaxTokens) {
        PromptComposer.ComposedPrompt prompt = promptComposer.forUserMessage(userMessage);
        List<LlmChatMessage> conversation = prepareConversation(userId, userMessage, prompt);
        runLoop(userId, conversation, listener, true, requestedMaxTokens, prompt, new LinkedHashMap<>());
    }

    private List<LlmChatMessage> prepareConversation(Integer userId, String userMessage,
                                                     PromptComposer.ComposedPrompt prompt) {
        saveMessage(userId, "user", userMessage, null);
        List<ChatMessage> history = recentContext(userId);
        List<LlmChatMessage> messages = new ArrayList<>();
        messages.add(LlmChatMessage.system(prompt.systemPrompt()));
        messages.addAll(toLlmMessages(history));
        return messages;
    }

    /**
     * v0.1.2: only the most recent N user/assistant-text messages (plus the tool
     * exchanges inside that window) are sent to the LLM; the database keeps the
     * full history. The window is trimmed to start at a user message so the
     * conversation after the system prompt never opens with an orphan answer or
     * tool row.
     */
    private List<ChatMessage> recentContext(Integer userId) {
        int limit = Math.max(1, securityProperties.getChatContextMessages());
        return trimToFirstUserMessage(chatMessageRepository.findRecentContext(userId, limit));
    }

    private static List<ChatMessage> trimToFirstUserMessage(List<ChatMessage> window) {
        for (int i = 0; i < window.size(); i++) {
            if ("user".equals(window.get(i).getRole())) {
                return i == 0 ? window : new ArrayList<>(window.subList(i, window.size()));
            }
        }
        return List.of();
    }

    private String runLoop(Integer userId,
                           List<LlmChatMessage> conversation,
                           AgentEventListener listener,
                           boolean streaming,
                           Integer requestedMaxTokens,
                           PromptComposer.ComposedPrompt prompt,
                           Map<String, String> usedVersions) {
        LlmClient client = llmClientFactory.getClient("chat");
        if (client instanceof FallbackLlmClient || !client.isAvailable()) {
            throw new LlmUnavailableException("LLM 不可用，请稍后重试");
        }
        usedVersions.putAll(prompt.versions());
        listener.onPromptVersions(new LinkedHashMap<>(usedVersions));
        boolean recommendationContractLoaded = usedVersions.containsKey(PromptId.DIRECTION_EXPLANATION.name())
                && usedVersions.containsKey(PromptId.PATH_SUGGESTION.name());

        List<LlmChatMessage> context = new ArrayList<>(conversation);
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmRequestDto request = new LlmRequestDto();
            request.setUseCase("chat");
            request.setMessages(context);
            request.setTools(toolRegistry.definitions());
            request.setMaxTokens(effectiveMaxTokens(requestedMaxTokens));

            LlmResponseDto response = streaming
                    ? callStreaming(client, request, listener)
                    : client.chat(request);
            if (response == null) {
                throw new LlmUnavailableException("LLM 不可用，请稍后重试");
            }
            if (response.isFailed() || response.isMocked()) {
                String code = response.getErrorCode() == null ? "LLM_UNAVAILABLE" : response.getErrorCode();
                throw new LlmUnavailableException(code, "LLM 不可用，请稍后重试");
            }

            if (response.hasToolCalls()) {
                // Persist a stable turn id so history reconstruction can keep
                // separate LLM rounds separate even when they each call tools.
                String toolTurnId = UUID.randomUUID().toString();
                List<LlmToolCall> turnToolCalls = new ArrayList<>();
                List<LlmChatMessage> turnToolResults = new ArrayList<>();
                for (LlmToolCall call : response.getToolCalls()) {
                    String callId = call.getId() == null || call.getId().isBlank()
                            ? "call-" + UUID.randomUUID()
                            : call.getId();
                    String toolName = call.name();
                    String argumentsJson = call.arguments() == null || call.arguments().isBlank()
                            ? "{}"
                            : call.arguments();

                    // Send both process events before persisting: if the browser has
                    // disconnected, the event send throws and no partial tool exchange
                    // is written.
                    listener.onToolCall(callId, toolName,
                            toolRegistry.displayDescription(toolName), argumentsJson);

                    ToolResult result = toolRegistry.execute(
                            toolName, new AgentToolContext(userId), argumentsJson);
                    String resultJson = serializeToolResult(callId, toolTurnId, result);
                    listener.onToolResult(callId, toolName, result.ok(), resultJson);

                    // Both events were delivered: persist the matching pair atomically.
                    persistToolExchange(userId, toolTurnId, callId, toolName, argumentsJson, resultJson);

                    if (isAwaitingConfirmation(result)) {
                        String proposalsJson = toJson(awaitingCandidates(result));
                        listener.onProfileChangeProposal(proposalsJson);
                        listener.onAwaitingConfirmation(proposalsJson);
                        return null;
                    }

                    turnToolCalls.add(new LlmToolCall(callId, toolName, argumentsJson));
                    turnToolResults.add(LlmChatMessage.toolResult(callId, toolName, resultJson));
                }
                // OpenAI/DeepSeek requires one assistant message carrying all tool
                // calls from this model turn, followed by all matching tool results.
                context.add(LlmChatMessage.assistantToolCalls(turnToolCalls));
                context.addAll(turnToolResults);

                if (!recommendationContractLoaded && turnToolCalls.stream()
                        .anyMatch(call -> RECOMMENDATION_TOOLS.contains(call.name()))) {
                    PromptComposer.ComposedPrompt contract = promptComposer.recommendationContract();
                    usedVersions.putAll(contract.versions());
                    if (!context.isEmpty()) {
                        LlmChatMessage system = context.get(0);
                        context.set(0, LlmChatMessage.system(
                                (system.getContent() == null ? "" : system.getContent())
                                        + "\n\n" + contract.systemPrompt()));
                    }
                    listener.onPromptVersions(new LinkedHashMap<>(usedVersions));
                    recommendationContractLoaded = true;
                }
                continue;
            }

            String content = response.getContent() == null ? "" : response.getContent();
            String createTime = LocalDateTime.now(DISPLAY_ZONE).format(TIME_FORMATTER);
            // Persist only after the complete message was delivered to the client.
            // A failed SSE send throws and leaves no partial assistant row behind.
            listener.onAssistantMessage(content, createTime);
            saveMessage(userId, "assistant", content, promptComposer.fingerprint(usedVersions));
            return content;
        }

        throw new LlmUnavailableException("MAX_TOOL_ROUNDS", "工具调用轮数超过上限，请稍后重试");
    }

    private boolean isAwaitingConfirmation(ToolResult result) {
        if (result == null || !result.ok() || !(result.data() instanceof Map<?, ?> data)) {
            return false;
        }
        return Boolean.TRUE.equals(data.get("awaitingConfirmation"));
    }

    private Object awaitingCandidates(ToolResult result) {
        if (result.data() instanceof Map<?, ?> data && data.get("candidates") != null) {
            return data.get("candidates");
        }
        return List.of();
    }

    private int effectiveMaxTokens(Integer requestedMaxTokens) {
        int configuredLimit = securityProperties.getChatMaxOutputTokens();
        if (requestedMaxTokens == null) {
            return configuredLimit;
        }
        return Math.max(1, Math.min(requestedMaxTokens, configuredLimit));
    }

    private LlmResponseDto callStreaming(LlmClient client,
                                         LlmRequestDto request,
                                         AgentEventListener listener) {
        AtomicReference<LlmResponseDto> completed = new AtomicReference<>();
        AtomicReference<LlmResponseDto> error = new AtomicReference<>();
        AtomicReference<Instant> thinkingStart = new AtomicReference<>();
        AtomicBoolean thinkingEnded = new AtomicBoolean(false);

        LlmStreamListener streamListener = new LlmStreamListener() {
            @Override
            public void onThinkingStart() {
                Instant now = Instant.now();
                thinkingStart.compareAndSet(null, now);
                listener.onThinkingStart(thinkingStart.get());
            }

            @Override
            public void onThinkingEnd() {
                endThinking(thinkingStart, thinkingEnded, listener);
            }

            @Override
            public void onContentDelta(String delta) {
                listener.onToken(delta);
            }

            @Override
            public void onToolCallDelta() {
                // Do not end the thinking timer on a tool-call delta; the provider
                // ends it via onThinkingEnd (reasoning marker or stream end).
            }

            @Override
            public void onComplete(LlmResponseDto response) {
                completed.set(response);
            }

            @Override
            public void onError(LlmResponseDto response) {
                error.set(response);
            }
        };

        client.chatStream(request, streamListener);
        if (error.get() != null) {
            return error.get();
        }
        endThinking(thinkingStart, thinkingEnded, listener);
        return completed.get();
    }

    private void endThinking(AtomicReference<Instant> thinkingStart,
                             AtomicBoolean thinkingEnded,
                             AgentEventListener listener) {
        Instant start = thinkingStart.get();
        if (start != null && thinkingEnded.compareAndSet(false, true)) {
            listener.onThinkingEnd(Duration.between(start, Instant.now()).toMillis());
        }
    }

    private List<LlmChatMessage> toLlmMessages(List<ChatMessage> history) {
        List<LlmChatMessage> messages = new ArrayList<>();
        Map<String, ToolExchange> exchanges = new LinkedHashMap<>();
        for (ChatMessage message : history) {
            String role = message.getRole();
            if ("assistant".equals(role)) {
                ToolCallRecord record = parseToolCall(message.getContent());
                if (record != null) {
                    exchanges.computeIfAbsent(record.turnId(), key -> new ToolExchange())
                            .calls.add(new LlmToolCall(record.callId(), record.name(), record.argumentsJson()));
                    continue;
                }
                flushToolExchanges(messages, exchanges);
                messages.add(LlmChatMessage.assistant(message.getContent()));
            } else if ("tool".equals(role)) {
                ToolResultRecord record = parseToolResult(message.getContent());
                exchanges.computeIfAbsent(record == null ? null : record.turnId(), key -> new ToolExchange())
                        .results.add(LlmChatMessage.toolResult(
                                record == null ? null : record.callId(),
                                null,
                                message.getContent()));
            } else if ("user".equals(role)) {
                flushToolExchanges(messages, exchanges);
                messages.add(LlmChatMessage.user(message.getContent()));
            } else if ("system".equals(role)) {
                flushToolExchanges(messages, exchanges);
                messages.add(LlmChatMessage.system(message.getContent()));
            }
        }
        flushToolExchanges(messages, exchanges);
        return messages;
    }

    /**
     * Rebuild one protocol-valid assistant tool_calls message per persisted
     * turn id, followed by the matching tool results. Legacy rows without a
     * turn id fall back to one consecutive group.
     */
    private void flushToolExchanges(List<LlmChatMessage> messages, Map<String, ToolExchange> exchanges) {
        for (ToolExchange exchange : exchanges.values()) {
            Map<String, LlmChatMessage> resultsByCallId = new HashMap<>();
            for (LlmChatMessage result : exchange.results) {
                if (result.getToolCallId() != null) {
                    resultsByCallId.put(result.getToolCallId(), result);
                }
            }
            List<LlmToolCall> pairedCalls = new ArrayList<>();
            List<LlmChatMessage> pairedResults = new ArrayList<>();
            for (LlmToolCall call : exchange.calls) {
                LlmChatMessage result = resultsByCallId.get(call.getId());
                if (result != null) {
                    pairedCalls.add(call);
                    pairedResults.add(result);
                }
            }
            if (!pairedCalls.isEmpty()) {
                messages.add(LlmChatMessage.assistantToolCalls(pairedCalls));
                messages.addAll(pairedResults);
            }
        }
        exchanges.clear();
    }

    private ToolCallRecord parseToolCall(String content) {
        if (content == null || !content.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(content);
            if (!"tool_call".equals(node.path("type").asText())) {
                return null;
            }
            String turnId = node.path("turnId").asText(null);
            String name = node.path("name").asText(null);
            String callId = node.path("callId").asText(null);
            JsonNode arguments = node.get("arguments");
            String argumentsJson = arguments == null || arguments.isNull() ? "{}" : arguments.toString();
            return new ToolCallRecord(turnId, callId, name, argumentsJson);
        } catch (Exception e) {
            LOG.warn("Unable to parse persisted tool_call message: {}", e.getMessage());
            return null;
        }
    }

    private ToolResultRecord parseToolResult(String content) {
        if (content == null || !content.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(content);
            if (!"tool_result".equals(node.path("type").asText())) {
                return null;
            }
            return new ToolResultRecord(node.path("turnId").asText(null), node.path("callId").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    private void persistToolExchange(Integer userId, String turnId, String callId, String toolName,
                                     String argumentsJson, String resultJson) {
        ChatMessage callMessage = newMessage(userId, "assistant",
                buildToolCallPayload(turnId, callId, toolName, argumentsJson), null);
        ChatMessage resultMessage = newMessage(userId, "tool", resultJson, null);
        chatMessageRepository.saveMessages(List.of(callMessage, resultMessage));
    }

    private String buildToolCallPayload(String turnId, String callId, String toolName, String argumentsJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_call");
        payload.put("turnId", turnId);
        payload.put("name", toolName);
        payload.put("callId", callId);
        try {
            payload.put("arguments", MAPPER.readTree(argumentsJson));
        } catch (Exception e) {
            payload.put("arguments", argumentsJson);
        }
        return toJson(payload);
    }

    private String serializeToolResult(String callId, String turnId, ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_result");
        payload.put("turnId", turnId);
        payload.put("callId", callId);
        payload.put("ok", result.ok());
        if (result.data() != null) {
            payload.put("data", result.data());
        }
        if (result.errorCode() != null) {
            payload.put("errorCode", result.errorCode());
        }
        if (result.message() != null) {
            payload.put("message", result.message());
        }
        return toJson(payload);
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            LOG.error("Unable to serialize agent payload", e);
            return "{}";
        }
    }

    private void saveMessage(Integer userId, String role, String content, String promptVersion) {
        chatMessageRepository.saveMessage(newMessage(userId, role, content, promptVersion));
    }

    private ChatMessage newMessage(Integer userId, String role, String content, String promptVersion) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setPromptVersion(promptVersion);
        message.setCreateTime(new Timestamp(System.currentTimeMillis()));
        return message;
    }

    private record ToolCallRecord(String turnId, String callId, String name, String argumentsJson) {
    }

    private record ToolResultRecord(String turnId, String callId) {
    }

    private static final class ToolExchange {
        private final List<LlmToolCall> calls = new ArrayList<>();
        private final List<LlmChatMessage> results = new ArrayList<>();
    }
}
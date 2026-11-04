package org.example.lifecomposer.Service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.lifecomposer.config.LlmConfig.UseCaseConfig;
import org.example.lifecomposer.dto.ConversationMessage;
import org.example.lifecomposer.dto.LlmChatMessage;
import org.example.lifecomposer.dto.LlmRequestDto;
import org.example.lifecomposer.dto.LlmResponseDto;
import org.example.lifecomposer.dto.LlmToolCall;
import org.example.lifecomposer.dto.LlmToolDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible LLM adapter. Supports plain chat, tool definitions and
 * streaming SSE. Never fabricates assistant content: failures are returned as
 * an explicit {@code LLM_UNAVAILABLE} error DTO.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger LOG = LogManager.getLogger(OpenAiCompatibleLlmClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_ERROR_BODY_LEN = 200;
    private static final String ERROR_CODE_UNAVAILABLE = "LLM_UNAVAILABLE";

    private final UseCaseConfig config;
    private static final long MAX_BACKOFF_MILLIS = 5000L;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    /** Pauses between retry attempts; replaced in tests so they do not really sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final Sleeper sleeper;
    private final Gson gson = new Gson();

    public OpenAiCompatibleLlmClient(UseCaseConfig config) {
        this.config = config;
        this.apiKey = resolveApiKey(config.getApiKeyEnv());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeoutMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getTimeoutMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getTimeoutMillis(), TimeUnit.MILLISECONDS)
                .build();
        this.sleeper = Thread::sleep;
    }

    OpenAiCompatibleLlmClient(UseCaseConfig config, OkHttpClient client, String apiKey) {
        this(config, client, apiKey, Thread::sleep);
    }

    OpenAiCompatibleLlmClient(UseCaseConfig config, OkHttpClient client, String apiKey, Sleeper sleeper) {
        this.config = config;
        this.apiKey = apiKey;
        this.httpClient = client;
        this.sleeper = sleeper;
    }

    /**
     * Sends the request, retrying connection failures and HTTP 429/5xx with
     * exponential backoff. This only covers the phase before any body byte has
     * been read, so a stream that already started is never replayed. Read
     * timeouts are not retried: the provider may still be generating, and
     * repeating would multiply the wait and the cost.
     *
     * @return the final response (successful, or the last failing one once the
     *         attempts are used up; the caller owns and closes it)
     */
    private Response executeWithRetry(Request request) throws IOException {
        int maxRetries = Math.max(0, config.getMaxRetries());
        for (int attempt = 0; ; attempt++) {
            long delay;
            String reason;
            try {
                Response response = httpClient.newCall(request).execute();
                int code = response.code();
                if (attempt >= maxRetries || !RETRYABLE_STATUS.contains(code)) {
                    return response;
                }
                delay = backoffMillis(attempt, response.header("Retry-After"));
                reason = "HTTP " + code;
                response.close();
            } catch (IOException e) {
                if (attempt >= maxRetries || !isRetryableNetworkError(e)) {
                    throw e;
                }
                delay = backoffMillis(attempt, null);
                reason = e.getClass().getSimpleName();
            }
            LOG.warn("LLM [{}] {} (model={}), retry {}/{} in {} ms",
                    config.getProvider(), reason, config.getModel(), attempt + 1, maxRetries, delay);
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting to retry");
            }
        }
    }

    private long backoffMillis(int attempt, String retryAfterHeader) {
        if (retryAfterHeader != null) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                if (seconds >= 0) {
                    return Math.min(seconds * 1000L, MAX_BACKOFF_MILLIS);
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date form or garbage: fall back to exponential backoff.
            }
        }
        long base = Math.max(0, config.getRetryBaseDelayMillis());
        return Math.min(base << Math.min(attempt, 10), MAX_BACKOFF_MILLIS);
    }

    private static boolean isRetryableNetworkError(IOException e) {
        if (e instanceof ConnectException || e instanceof UnknownHostException
                || e instanceof NoRouteToHostException) {
            return true;
        }
        // Only a connect-phase timeout is safe to repeat; a read timeout is not.
        return e instanceof SocketTimeoutException
                && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("connect");
    }

    private static String resolveApiKey(String envVarName) {
        if (envVarName == null || envVarName.isBlank()) {
            return null;
        }
        String value = System.getenv(envVarName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    @Override
    public LlmResponseDto chat(LlmRequestDto request) {
        if (!config.isEnabled()) {
            return buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "LLM use case is disabled in configuration");
        }
        boolean requiresApiKey = !"ollama".equalsIgnoreCase(config.getProvider());
        if (requiresApiKey && apiKey == null) {
            return buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "API key env var '" + config.getApiKeyEnv() + "' is not set or empty");
        }

        String url = config.getBaseUrl() + "/chat/completions";
        String json = gson.toJson(buildPayload(request, false));

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json; charset=utf-8");
        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = executeWithRetry(requestBuilder.build())) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String message = "HTTP " + response.code() + " from " + config.getBaseUrl()
                        + (body.isBlank() ? "" : " — " + truncate(body));
                LOG.warn("LLM [{}] HTTP {} failure (model={})",
                        config.getProvider(), response.code(), config.getModel());
                return buildErrorResponse(ERROR_CODE_UNAVAILABLE, message);
            }
            return parseChatResponse(body);
        } catch (IOException e) {
            String message = "Network error: " + e.getClass().getSimpleName();
            LOG.warn("LLM [{}] network error (model={}, url={})",
                    config.getProvider(), config.getModel(), url);
            return buildErrorResponse(ERROR_CODE_UNAVAILABLE, message);
        }
    }

    private LlmResponseDto parseChatResponse(String body) {
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            JsonArray choices = root != null ? root.getAsJsonArray("choices") : null;
            if (choices == null || choices.isEmpty()) {
                return buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                        "Unexpected response format from provider");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) {
                return buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                        "Provider response has no message object");
            }
            LlmResponseDto dto = new LlmResponseDto();
            dto.setMocked(false);
            dto.setFailed(false);
            dto.setProvider(config.getProvider());
            dto.setModel(config.getModel());
            dto.setContent(optString(message, "content"));
            dto.setReasoningContent(optString(message, "reasoning_content"));
            dto.setToolCalls(parseToolCalls(message));
            dto.setFinishReason(optString(choice, "finish_reason"));
            return dto;
        } catch (RuntimeException e) {
            return buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "Invalid response format from provider");
        }
    }

    @Override
    public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
        if (!config.isEnabled()) {
            listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "LLM use case is disabled in configuration"));
            return;
        }
        boolean requiresApiKey = !"ollama".equalsIgnoreCase(config.getProvider());
        if (requiresApiKey && apiKey == null) {
            listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "API key env var '" + config.getApiKeyEnv() + "' is not set or empty"));
            return;
        }

        String url = config.getBaseUrl() + "/chat/completions";
        String json = gson.toJson(buildPayload(request, true));

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream");
        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = executeWithRetry(requestBuilder.build())) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                        "HTTP " + response.code() + (body.isBlank() ? "" : " — " + truncate(body))));
                return;
            }
            if (response.body() == null) {
                listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE, "empty streaming response"));
                return;
            }
            try (BufferedReader reader = new BufferedReader(response.body().charStream())) {
                consumeStream(reader, listener);
            }
        } catch (IOException e) {
            listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "Network error: " + e.getClass().getSimpleName()));
        }
    }

    void consumeStream(BufferedReader reader, LlmStreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, PartialToolCall> partials = new LinkedHashMap<>();
        String finishReason = null;
        boolean reasoningSeen = false;
        boolean thinkingEnded = false;
        boolean sawValidChunk = false;
        boolean sawDone = false;

        String line;
        while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    break;
                }
                JsonObject chunk;
                try {
                    chunk = gson.fromJson(data, JsonObject.class);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (chunk == null) {
                    continue;
                }
                JsonArray choices;
                try {
                    choices = chunk.getAsJsonArray("choices");
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JsonObject choice;
                try {
                    choice = choices.get(0).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    continue;
                }
                sawValidChunk = true;
                String currentFinish = optString(choice, "finish_reason");
                if (currentFinish != null) {
                    finishReason = currentFinish;
                }
                JsonObject delta;
                try {
                    delta = choice.getAsJsonObject("delta");
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (delta == null) {
                    continue;
                }
                boolean hasReasoning = hasNonEmptyText(delta, "reasoning_content");
                boolean hasContent = hasNonEmptyText(delta, "content");
                boolean hasToolCalls = delta.has("tool_calls")
                        && delta.get("tool_calls").isJsonArray()
                        && !delta.getAsJsonArray("tool_calls").isEmpty();

                // The thinking timer starts only when the provider actually emits
                // reasoning content; plain content/tool_calls must not start it.
                if (hasReasoning) {
                    if (!reasoningSeen) {
                        reasoningSeen = true;
                        listener.onThinkingStart();
                    }
                    String piece = delta.get("reasoning_content").getAsString();
                    reasoning.append(piece);
                    listener.onReasoningDelta(piece);
                    if (!thinkingEnded && containsThinkingEndMarker(reasoning.toString())) {
                        thinkingEnded = true;
                        listener.onThinkingEnd();
                    }
                }
                if (hasContent) {
                    // Explicit end of the reasoning phase (providers that expose
                    // reasoning_content and content as separate streams).
                    if (reasoningSeen && !thinkingEnded) {
                        thinkingEnded = true;
                        listener.onThinkingEnd();
                    }
                    String piece = delta.get("content").getAsString();
                    content.append(piece);
                    listener.onContentDelta(piece);
                }
                if (hasToolCalls) {
                    listener.onToolCallDelta();
                    try {
                        accumulateToolCalls(delta.getAsJsonArray("tool_calls"), partials);
                    } catch (RuntimeException ignored) {
                        // Malformed tool-call fragment; ignore it and keep streaming.
                    }
                }
        }

        if (!sawValidChunk || (!sawDone && finishReason == null)) {
            listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "empty or prematurely ended streaming response"));
            return;
        }
        if (content.length() == 0 && partials.isEmpty()) {
            listener.onError(buildErrorResponse(ERROR_CODE_UNAVAILABLE,
                    "streaming response contained no answer"));
            return;
        }

        if (reasoningSeen && !thinkingEnded) {
            thinkingEnded = true;
            listener.onThinkingEnd();
        }

        LlmResponseDto dto = new LlmResponseDto();
        dto.setMocked(false);
        dto.setFailed(false);
        dto.setProvider(config.getProvider());
        dto.setModel(config.getModel());
        dto.setContent(content.length() == 0 ? null : content.toString());
        dto.setReasoningContent(reasoning.length() == 0 ? null : reasoning.toString());
        dto.setFinishReason(finishReason);
        if (!partials.isEmpty()) {
            List<LlmToolCall> calls = new ArrayList<>();
            for (PartialToolCall partial : partials.values()) {
                calls.add(new LlmToolCall(partial.id, partial.name, partial.arguments.toString()));
            }
            dto.setToolCalls(calls);
        }
        listener.onComplete(dto);
    }

    private static boolean containsThinkingEndMarker(String reasoningText) {
        if (reasoningText == null || reasoningText.isEmpty()) {
            return false;
        }
        String lower = reasoningText.toLowerCase(Locale.ROOT);
        return lower.contains("</think")
                || lower.contains("done thinking")
                || lower.contains("<|end_of_thinking|>");
    }

    private void accumulateToolCalls(JsonArray toolCallDeltas, Map<Integer, PartialToolCall> partials) {
        for (JsonElement element : toolCallDeltas) {
            JsonObject toolCall = element.getAsJsonObject();
            int index = hasNonNull(toolCall, "index") ? toolCall.get("index").getAsInt() : partials.size();
            PartialToolCall partial = partials.computeIfAbsent(index, key -> new PartialToolCall());
            if (hasNonNull(toolCall, "id")) {
                partial.id = toolCall.get("id").getAsString();
            }
            JsonObject function = toolCall.getAsJsonObject("function");
            if (function != null) {
                if (hasNonNull(function, "name")) {
                    partial.name = function.get("name").getAsString();
                }
                if (hasNonNull(function, "arguments")) {
                    partial.arguments.append(function.get("arguments").getAsString());
                }
            }
        }
    }

    private JsonObject buildPayload(LlmRequestDto request, boolean stream) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.getModel());

        if (config.getTemperature() != null) {
            root.addProperty("temperature", config.getTemperature());
        }

        JsonArray messages = new JsonArray();
        if (request != null && request.getMessages() != null && !request.getMessages().isEmpty()) {
            for (LlmChatMessage message : request.getMessages()) {
                messages.add(toJsonMessage(message));
            }
        } else {
            if (request != null && request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                JsonObject system = new JsonObject();
                system.addProperty("role", "system");
                system.addProperty("content", request.getSystemPrompt());
                messages.add(system);
            }
            if (request != null && request.getConversationHistory() != null) {
                for (ConversationMessage historyMessage : request.getConversationHistory()) {
                    JsonObject history = new JsonObject();
                    history.addProperty("role", historyMessage.getRole());
                    history.addProperty("content", historyMessage.getContent());
                    messages.add(history);
                }
            }
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", request != null && request.getMessage() != null
                    ? request.getMessage()
                    : "");
            messages.add(user);
        }
        root.add("messages", messages);

        if (request != null && request.getTools() != null && !request.getTools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (LlmToolDefinition definition : request.getTools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", definition.getName());
                function.addProperty("description", definition.getDescription());
                function.add("parameters", gson.toJsonTree(definition.getParameters()));
                tool.add("function", function);
                tools.add(tool);
            }
            root.add("tools", tools);
            root.addProperty("tool_choice", "auto");
        }

        if (stream) {
            root.addProperty("stream", true);
        }
        if (request != null && request.getMaxTokens() != null) {
            root.addProperty("max_tokens", request.getMaxTokens());
        }
        return root;
    }

    private JsonObject toJsonMessage(LlmChatMessage message) {
        JsonObject json = new JsonObject();
        json.addProperty("role", message.getRole());
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (LlmToolCall call : message.getToolCalls()) {
                JsonObject toolCall = new JsonObject();
                if (call.getId() != null) {
                    toolCall.addProperty("id", call.getId());
                }
                toolCall.addProperty("type", call.getType() == null ? "function" : call.getType());
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                function.addProperty("arguments", call.arguments() == null ? "{}" : call.arguments());
                toolCall.add("function", function);
                toolCalls.add(toolCall);
            }
            json.add("tool_calls", toolCalls);
            json.add("content", message.getContent() == null ? JsonNull.INSTANCE
                    : gson.toJsonTree(message.getContent()));
        } else {
            json.addProperty("content", message.getContent() == null ? "" : message.getContent());
        }
        if (message.getToolCallId() != null) {
            json.addProperty("tool_call_id", message.getToolCallId());
        }
        if (message.getName() != null) {
            json.addProperty("name", message.getName());
        }
        return json;
    }

    private List<LlmToolCall> parseToolCalls(JsonObject message) {
        if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
            return null;
        }
        JsonArray array = message.getAsJsonArray("tool_calls");
        if (array.isEmpty()) {
            return null;
        }
        List<LlmToolCall> calls = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject toolCall = element.getAsJsonObject();
            String id = optString(toolCall, "id");
            String type = optString(toolCall, "type");
            JsonObject function = toolCall.getAsJsonObject("function");
            String name = function != null ? optString(function, "name") : null;
            String arguments = function != null ? optString(function, "arguments") : null;
            LlmToolCall call = new LlmToolCall(id, name, arguments);
            if (type != null) {
                call.setType(type);
            }
            calls.add(call);
        }
        return calls;
    }

    private LlmResponseDto buildErrorResponse(String code, String message) {
        LlmResponseDto dto = new LlmResponseDto();
        dto.setMocked(false);
        dto.setFailed(true);
        dto.setErrorCode(code);
        dto.setErrorMessage(message);
        dto.setProvider(config.getProvider());
        dto.setModel(config.getModel());
        return dto;
    }

    private static String optString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        try {
            return object.get(field).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasNonNull(JsonObject object, String field) {
        return object != null && object.has(field) && !object.get(field).isJsonNull();
    }

    /** Empty strings are common role-only deltas and must not start/end thinking. */
    private static boolean hasNonEmptyText(JsonObject object, String field) {
        if (!hasNonNull(object, field)) {
            return false;
        }
        try {
            String value = object.get(field).getAsString();
            return value != null && !value.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String truncate(String value) {
        return value.length() > MAX_ERROR_BODY_LEN
                ? value.substring(0, MAX_ERROR_BODY_LEN) + "...(truncated)"
                : value;
    }

    @Override
    public boolean isAvailable() {
        if (!config.isEnabled()) {
            return false;
        }
        if ("ollama".equalsIgnoreCase(config.getProvider())) {
            return true;
        }
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getProvider() {
        return config.getProvider();
    }

    @Override
    public String getModel() {
        return config.getModel();
    }

    private static final class PartialToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}

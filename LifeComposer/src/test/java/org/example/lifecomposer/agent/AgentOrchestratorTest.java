package org.example.lifecomposer.agent;

import com.google.gson.JsonObject;
import org.example.lifecomposer.Entity.ChatMessage;
import org.example.lifecomposer.Repository.ChatMessageRepository;
import org.example.lifecomposer.Service.FallbackLlmClient;
import org.example.lifecomposer.Service.LlmClient;
import org.example.lifecomposer.Service.LlmClientFactory;
import org.example.lifecomposer.Service.LlmStreamListener;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.example.lifecomposer.dto.ChatResponse;
import org.example.lifecomposer.dto.LlmChatMessage;
import org.example.lifecomposer.dto.LlmRequestDto;
import org.example.lifecomposer.dto.LlmResponseDto;
import org.example.lifecomposer.dto.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    @Test
    void runsMultiRoundToolUseAndPersistsStructuredMessages() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                toolCallResponse(),
                textResponse("最终回答")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of(new FakeSearchResourcesTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(repository, factory, registry, new AppSecurityProperties());

        ChatResponse response = orchestrator.sendMessage(1, "帮我找数学建模");

        assertEquals("最终回答", response.getContent());
        assertNotNull(response.getPromptVersions());
        assertTrue(response.getPromptVersions().containsKey("PROFILE_EXTRACTION"),
                "普通聊天也应启用结构化画像提取 Prompt");
        assertEquals(4, stored.size());
        assertEquals("user", stored.get(0).getRole());
        assertEquals("帮我找数学建模", stored.get(0).getContent());

        assertEquals("assistant", stored.get(1).getRole());
        assertTrue(stored.get(1).getContent().contains("\"type\":\"tool_call\""));
        assertTrue(stored.get(1).getContent().contains("\"callId\":\"call-1\""));

        assertEquals("tool", stored.get(2).getRole());
        assertTrue(stored.get(2).getContent().contains("\"type\":\"tool_result\""));
        assertTrue(stored.get(2).getContent().contains("\"callId\":\"call-1\""));

        assertEquals("assistant", stored.get(3).getRole());
        assertEquals("最终回答", stored.get(3).getContent());
        assertNotNull(stored.get(3).getPromptVersion(), "assistant 消息应持久化 Prompt 版本指纹");
        assertTrue(stored.get(3).getPromptVersion().contains("CHAT_SYSTEM="));

        // Second LLM call must receive the reconstructed tool result message.
        List<LlmRequestDto> requests = client.requests;
        assertEquals(2, requests.size());
        boolean hasToolMessage = requests.get(1).getMessages().stream()
                .anyMatch(message -> "tool".equals(message.getRole()));
        assertTrue(hasToolMessage);
        boolean hasAssistantToolCalls = requests.get(1).getMessages().stream()
                .anyMatch(message -> message.getToolCalls() != null && !message.getToolCalls().isEmpty());
        assertTrue(hasAssistantToolCalls);
    }

    @Test
    void groupsMultipleToolCallsIntoOneAssistantMessage() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                multiToolCallResponse(),
                textResponse("done")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of(new FakeSearchResourcesTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(repository, factory, registry, new AppSecurityProperties());

        assertEquals("done", orchestrator.sendMessage(1, "查两个方向").getContent());

        List<LlmChatMessage> secondMessages = client.requests.get(1).getMessages();
        long assistantToolCallMessages = secondMessages.stream()
                .filter(message -> message.getToolCalls() != null && !message.getToolCalls().isEmpty())
                .count();
        long toolMessages = secondMessages.stream()
                .filter(message -> "tool".equals(message.getRole()))
                .count();

        assertEquals(1, assistantToolCallMessages);
        assertEquals(2, toolMessages);
        LlmChatMessage assistantWithTools = secondMessages.stream()
                .filter(message -> message.getToolCalls() != null && !message.getToolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
        assertEquals(2, assistantWithTools.getToolCalls().size());
        assertEquals("call-1", assistantWithTools.getToolCalls().get(0).getId());
        assertEquals("call-2", assistantWithTools.getToolCalls().get(1).getId());
    }

    @Test
    void keepsSeparateToolRoundsSeparate() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                singleToolCallResponse("call-1", "数学建模"),
                singleToolCallResponse("call-2", "蓝桥杯"),
                textResponse("done")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of(new FakeSearchResourcesTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(repository, factory, registry, new AppSecurityProperties());

        assertEquals("done", orchestrator.sendMessage(1, "两轮各调用一个工具").getContent());

        List<LlmChatMessage> thirdRequestMessages = client.requests.get(2).getMessages();
        List<LlmChatMessage> toolCallMessages = thirdRequestMessages.stream()
                .filter(message -> message.getToolCalls() != null && !message.getToolCalls().isEmpty())
                .toList();
        assertEquals(2, toolCallMessages.size());
        assertEquals(1, toolCallMessages.get(0).getToolCalls().size());
        assertEquals(1, toolCallMessages.get(1).getToolCalls().size());
        assertEquals("call-1", toolCallMessages.get(0).getToolCalls().get(0).getId());
        assertEquals("call-2", toolCallMessages.get(1).getToolCalls().get(0).getId());
        assertEquals(2, thirdRequestMessages.stream()
                .filter(message -> "tool".equals(message.getRole()))
                .count());
    }

    @Test
    void forwardsThinkingEndFromProviderBeforeTokens() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        StreamingScriptedLlmClient client = new StreamingScriptedLlmClient();
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of());
        AgentOrchestrator orchestrator = new AgentOrchestrator(repository, factory, registry, new AppSecurityProperties());
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        orchestrator.streamMessage(1, "hi", listener);

        assertEquals(1, listener.thinkingStarts);
        assertEquals(1, listener.thinkingEnds);
        assertTrue(listener.tokens.contains("你好"));
        assertTrue(listener.order.indexOf("thinking_end") < listener.order.indexOf("token"),
                "thinking_end must be sent before the first visible token");
    }

    @Test
    void doesNotPersistAssistantMessageWhenSseSendFails() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(new CompletingStreamingLlmClient(textResponse("answer")));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties());
        AgentEventListener failingListener = new AgentEventListener() {
            @Override
            public void onAssistantMessage(String content, String createTime) {
                throw new RuntimeException("client disconnected");
            }
        };

        assertThrows(RuntimeException.class,
                () -> orchestrator.streamMessage(1, "hi", failingListener));

        assertEquals(1, stored.size());
        assertEquals("user", stored.get(0).getRole());
        assertTrue(stored.stream().noneMatch(message -> "assistant".equals(message.getRole())));
    }

    @Test
    void doesNotPersistToolCallWhenSseSendFails() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(new CompletingStreamingLlmClient(toolCallResponse()));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of(new FakeSearchResourcesTool())),
                new AppSecurityProperties());
        AgentEventListener failingListener = new AgentEventListener() {
            @Override
            public void onToolCall(String callId, String name, String description, String argumentsJson) {
                throw new RuntimeException("client disconnected");
            }
        };

        assertThrows(RuntimeException.class,
                () -> orchestrator.streamMessage(1, "hi", failingListener));

        assertEquals(1, stored.size());
        assertEquals("user", stored.get(0).getRole());
        assertTrue(stored.stream().noneMatch(message -> "assistant".equals(message.getRole())));
        assertTrue(stored.stream().noneMatch(message -> "tool".equals(message.getRole())));
    }

    @Test
    void throwsLlmUnavailableForFallbackClient() {
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        when(repository.findByUserId(1)).thenAnswer(invocation -> new ArrayList<>());
        when(repository.saveMessage(any(ChatMessage.class))).thenReturn(1);

        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(new FallbackLlmClient("deepseek", "deepseek-flash"));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties());

        assertThrows(LlmUnavailableException.class,
                () -> orchestrator.sendMessage(1, "你好"));
    }

    @Test
    void capsRequestedMaxTokensToConfiguredLimit() {
        ChatMessageRepository repository = mockRepository(new ArrayList<>());
        LlmClientFactory factory = mock(LlmClientFactory.class);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(textResponse("ok")));
        when(factory.getClient("chat")).thenReturn(client);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties());

        orchestrator.sendMessage(1, "hi", 9999);

        assertEquals(2048, client.requests.get(0).getMaxTokens());
    }

    @Test
    void emitsProfileChangeProposalAndStopsBeforeNextLlmRound() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                proposeToolCallResponse(),
                textResponse("should not be used")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of(new FakeProposeProfileUpdateTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, registry, new AppSecurityProperties());
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        orchestrator.streamMessage(1, "我会 C++ 和 Python", listener);

        assertEquals(1, listener.proposalEvents, "应发送 profile_change_proposal");
        assertEquals(1, listener.awaitingEvents, "应发送 awaiting_confirmation");
        assertTrue(listener.proposalJson.contains("chg_test"), "事件应携带候选 id");
        assertEquals(1, client.requests.size(),
                "awaiting confirmation 后本轮应结束，不再发起下一轮 LLM 调用");
    }

    @Test
    void injectsRecommendationContractWhenRecommendationToolWasCalled() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);

        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                recommendationToolCallResponse(),
                textResponse("done")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        ToolRegistry registry = new ToolRegistry(List.of(new FakeListGrowthDirectionsTool()));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, registry, new AppSecurityProperties());

        ChatResponse response = orchestrator.sendMessage(1, "帮我看看");
        assertEquals("done", response.getContent());

        List<LlmChatMessage> secondRequest = client.requests.get(1).getMessages();
        boolean hasContract = secondRequest.stream()
                .filter(message -> "system".equals(message.getRole()))
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("direction_explanation")
                        && message.getContent().contains("path_suggestion"));
        assertTrue(hasContract, "实际调用推荐工具后，后续轮次应补上方向解释/路径建议契约");
        assertTrue(response.getPromptVersions().containsKey("DIRECTION_EXPLANATION"));
        assertTrue(response.getPromptVersions().containsKey("PATH_SUGGESTION"));
    }

    @Test
    void keepsSmallerClientMaxTokens() {
        ChatMessageRepository repository = mockRepository(new ArrayList<>());
        LlmClientFactory factory = mock(LlmClientFactory.class);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(textResponse("ok")));
        when(factory.getClient("chat")).thenReturn(client);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties());

        orchestrator.sendMessage(1, "hi", 100);

        assertEquals(100, client.requests.get(0).getMaxTokens());
    }

    private ChatMessageRepository mockRepository(List<ChatMessage> stored) {
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        when(repository.findByUserId(1)).thenAnswer(invocation -> new ArrayList<>(stored));
        when(repository.findRecentContext(eq(1), anyInt()))
                .thenAnswer(invocation -> recentWindow(stored, invocation.getArgument(1)));
        when(repository.saveMessage(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(stored.size() + 1);
            stored.add(message);
            return message.getId();
        });
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            for (ChatMessage message : messages) {
                message.setId(stored.size() + 1);
                stored.add(message);
            }
            return null;
        }).when(repository).saveMessages(anyList());
        return repository;
    }

    /** Mirrors ChatMessageRepository.findRecentContext over the in-memory rows. */
    private static List<ChatMessage> recentWindow(List<ChatMessage> stored, int limit) {
        int counted = 0;
        for (int i = stored.size() - 1; i >= 0; i--) {
            ChatMessage message = stored.get(i);
            boolean conversation = "user".equals(message.getRole())
                    || ("assistant".equals(message.getRole())
                    && !message.getContent().startsWith(ChatMessageRepository.TOOL_CALL_CONTENT_PREFIX));
            if (conversation && ++counted == Math.max(1, limit)) {
                return new ArrayList<>(stored.subList(i, stored.size()));
            }
        }
        return new ArrayList<>(stored);
    }

    private static ChatMessage row(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static String toolCallRow(String turnId, String callId, String name) {
        return "{\"type\":\"tool_call\",\"turnId\":\"" + turnId + "\",\"name\":\"" + name
                + "\",\"callId\":\"" + callId + "\",\"arguments\":{}}";
    }

    private static String toolResultRow(String turnId, String callId) {
        return "{\"type\":\"tool_result\",\"turnId\":\"" + turnId + "\",\"callId\":\"" + callId
                + "\",\"ok\":true,\"data\":{}}";
    }

    /** 20 past turns; every even turn calls one tool before answering. */
    private static List<ChatMessage> longHistory() {
        List<ChatMessage> stored = new ArrayList<>();
        for (int turn = 0; turn < 20; turn++) {
            stored.add(row("user", "问题" + turn));
            if (turn % 2 == 0) {
                stored.add(row("assistant", toolCallRow("t" + turn, "c" + turn, "search_resources")));
                stored.add(row("tool", toolResultRow("t" + turn, "c" + turn)));
            }
            stored.add(row("assistant", "回答" + turn));
        }
        for (int i = 0; i < stored.size(); i++) {
            stored.get(i).setId(i + 1);
        }
        return stored;
    }

    private static long conversationCount(List<LlmChatMessage> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.getRole())
                        || ("assistant".equals(m.getRole()) && m.getToolCalls() == null))
                .count();
    }

    @Test
    void sendsOnlyTheConfiguredNumberOfRecentMessagesToTheLlm() {
        List<ChatMessage> stored = longHistory();
        ChatMessageRepository repository = mockRepository(stored);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(textResponse("ok")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties());
        orchestrator.sendMessage(1, "最新问题");

        List<LlmChatMessage> sent = client.requests.get(0).getMessages();
        assertEquals("system", sent.get(0).getRole());
        assertEquals("user", sent.get(1).getRole(), "窗口必须从用户消息开始");
        assertEquals(15, conversationCount(sent));
        assertEquals("最新问题", sent.get(sent.size() - 1).getContent());
        assertTrue(sent.stream().noneMatch(m -> "问题0".equals(m.getContent())), "旧消息不应发送");

        // Tool exchanges inside the window stay paired: every tool_calls message
        // is immediately followed by its tool result.
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i).getToolCalls() != null) {
                assertEquals("tool", sent.get(i + 1).getRole());
                assertEquals(sent.get(i).getToolCalls().get(0).getId(), sent.get(i + 1).getToolCallId());
            }
        }
        assertTrue(sent.stream().anyMatch(m -> m.getToolCalls() != null), "窗口内的工具调用应保留");
        // The database still keeps the full history plus the new turn.
        assertEquals(longHistory().size() + 2, stored.size());
    }

    @Test
    void contextWindowBelowOneIsTreatedAsOne() {
        List<ChatMessage> stored = longHistory();
        ChatMessageRepository repository = mockRepository(stored);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(textResponse("ok")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setChatContextMessages(0);

        new AgentOrchestrator(repository, factory, new ToolRegistry(List.of()), properties)
                .sendMessage(1, "只有这一条");

        List<LlmChatMessage> sent = client.requests.get(0).getMessages();
        assertEquals(2, sent.size());
        assertEquals("只有这一条", sent.get(1).getContent());
    }

    @Test
    void continuationAfterProfileDecisionStillSeesTheProposalExchange() {
        List<ChatMessage> stored = longHistory();
        stored.add(row("user", "我会 Python"));
        stored.add(row("assistant", toolCallRow("tp", "cp", "propose_profile_update")));
        stored.add(row("tool", toolResultRow("tp", "cp")));
        ChatMessageRepository repository = mockRepository(stored);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(textResponse("已更新")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        new AgentOrchestrator(repository, factory, new ToolRegistry(List.of()), new AppSecurityProperties())
                .continueAfterProfileDecision(1, "用户已确认");

        List<LlmChatMessage> sent = client.requests.get(0).getMessages();
        assertTrue(sent.stream().anyMatch(m -> m.getToolCalls() != null
                && "propose_profile_update".equals(m.getToolCalls().get(0).name())));
        assertTrue(sent.stream().anyMatch(m -> "tool".equals(m.getRole()) && "cp".equals(m.getToolCallId())));
        assertEquals("用户已确认", sent.get(sent.size() - 1).getContent());
        assertEquals(15, conversationCount(sent) - 1, "续答上下文本身不计入窗口");
    }

    @Test
    void persistedToolCallPayloadStartsWithTheRepositoryPrefix() {
        List<ChatMessage> stored = new ArrayList<>();
        ChatMessageRepository repository = mockRepository(stored);
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(toolCallResponse(), textResponse("ok")));
        LlmClientFactory factory = mock(LlmClientFactory.class);
        when(factory.getClient("chat")).thenReturn(client);

        new AgentOrchestrator(repository, factory,
                new ToolRegistry(List.of(new FakeSearchResourcesTool())), new AppSecurityProperties())
                .sendMessage(1, "找资源");

        assertTrue(stored.get(1).getContent().startsWith(ChatMessageRepository.TOOL_CALL_CONTENT_PREFIX),
                "findRecentContext 依赖 tool_call 行以该前缀开头");
    }

    private LlmResponseDto proposeToolCallResponse() {
        LlmResponseDto response = new LlmResponseDto();
        response.setToolCalls(List.of(new LlmToolCall(
                "call-1", "propose_profile_update",
                "{\"changes\":[{\"field\":\"skillsJson\",\"newValue\":\"[\\\"C++\\\",\\\"Python\\\"]\"}]}")));
        return response;
    }

    private LlmResponseDto recommendationToolCallResponse() {
        LlmResponseDto response = new LlmResponseDto();
        response.setToolCalls(List.of(new LlmToolCall(
                "call-1", "list_growth_directions", "{}")));
        return response;
    }

    private LlmResponseDto toolCallResponse() {
        LlmResponseDto response = new LlmResponseDto();
        response.setToolCalls(List.of(new LlmToolCall(
                "call-1", "search_resources", "{\"keyword\":\"数学建模\"}")));
        return response;
    }

    private LlmResponseDto singleToolCallResponse(String callId, String keyword) {
        LlmResponseDto response = new LlmResponseDto();
        response.setToolCalls(List.of(new LlmToolCall(
                callId, "search_resources", "{\"keyword\":\"" + keyword + "\"}")));
        return response;
    }

    private LlmResponseDto multiToolCallResponse() {
        LlmResponseDto response = new LlmResponseDto();
        response.setToolCalls(List.of(
                new LlmToolCall("call-1", "search_resources", "{\"keyword\":\"数学建模\"}"),
                new LlmToolCall("call-2", "search_resources", "{\"keyword\":\"蓝桥杯\"}")));
        return response;
    }

    private LlmResponseDto textResponse(String content) {
        LlmResponseDto response = new LlmResponseDto();
        response.setContent(content);
        return response;
    }

    private static final class ScriptedLlmClient implements LlmClient {

        private final List<LlmResponseDto> responses;
        private final List<LlmRequestDto> requests = new ArrayList<>();
        private int index;

        private ScriptedLlmClient(List<LlmResponseDto> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResponseDto chat(LlmRequestDto request) {
            requests.add(request);
            int current = Math.min(index, responses.size() - 1);
            index++;
            return responses.get(current);
        }

        @Override
        public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
            listener.onComplete(chat(request));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getProvider() {
            return "deepseek";
        }

        @Override
        public String getModel() {
            return "deepseek-flash";
        }
    }

    private static final class StreamingScriptedLlmClient implements LlmClient {

        @Override
        public LlmResponseDto chat(LlmRequestDto request) {
            throw new UnsupportedOperationException("chat() not used in streaming test");
        }

        @Override
        public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
            listener.onThinkingStart();
            listener.onReasoningDelta("thinking about the answer");
            listener.onThinkingEnd();
            listener.onContentDelta("你好");
            LlmResponseDto response = new LlmResponseDto();
            response.setContent("你好");
            listener.onComplete(response);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getProvider() {
            return "deepseek";
        }

        @Override
        public String getModel() {
            return "deepseek-flash";
        }
    }

    private static final class CompletingStreamingLlmClient implements LlmClient {

        private final LlmResponseDto response;

        private CompletingStreamingLlmClient(LlmResponseDto response) {
            this.response = response;
        }

        @Override
        public LlmResponseDto chat(LlmRequestDto request) {
            return response;
        }

        @Override
        public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
            listener.onComplete(response);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getProvider() {
            return "deepseek";
        }

        @Override
        public String getModel() {
            return "deepseek-flash";
        }
    }

    private static final class RecordingAgentEventListener implements AgentEventListener {

        private int thinkingStarts;
        private int thinkingEnds;
        private int proposalEvents;
        private int awaitingEvents;
        private String proposalJson;
        private final List<String> tokens = new ArrayList<>();
        private final List<String> order = new ArrayList<>();

        @Override
        public void onProfileChangeProposal(String proposalsJson) {
            proposalEvents++;
            proposalJson = proposalsJson;
        }

        @Override
        public void onAwaitingConfirmation(String proposalsJson) {
            awaitingEvents++;
        }

        @Override
        public void onThinkingStart(java.time.Instant startedAt) {
            thinkingStarts++;
            order.add("thinking_start");
        }

        @Override
        public void onThinkingEnd(long elapsedMs) {
            thinkingEnds++;
            order.add("thinking_end");
        }

        @Override
        public void onToken(String delta) {
            tokens.add(delta);
            order.add("token");
        }
    }

    private static final class FakeProposeProfileUpdateTool implements AgentTool {

        @Override
        public String name() {
            return "propose_profile_update";
        }

        @Override
        public String description() {
            return "提议画像变更";
        }

        @Override
        public String displayDescription() {
            return "提议画像变更";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            // Mirrors the real tool's top level: the model passes a "changes" array.
            return Map.of(
                    "type", "object",
                    "properties", Map.of("changes", Map.of("type", "array")),
                    "required", List.of("changes"),
                    "additionalProperties", false);
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            return ToolResult.ok(Map.of(
                    "status", "PENDING_CONFIRMATION",
                    "awaitingConfirmation", true,
                    "created", 1,
                    "candidates", List.of(Map.of(
                            "candidateId", "chg_test",
                            "field", "skillsJson",
                            "oldValue", "",
                            "newValue", "[\"C++\",\"Python\"]",
                            "status", "PENDING_CONFIRMATION"))));
        }
    }

    private static final class FakeListGrowthDirectionsTool implements AgentTool {

        @Override
        public String name() {
            return "list_growth_directions";
        }

        @Override
        public String description() {
            return "列出成长方向";
        }

        @Override
        public String displayDescription() {
            return "计算成长方向推荐";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            return ToolResult.ok(Map.of("count", 1, "recommendations", List.of()));
        }
    }

    private static final class FakeSearchResourcesTool implements AgentTool {

        @Override
        public String name() {
            return "search_resources";
        }

        @Override
        public String description() {
            return "搜索资源";
        }

        @Override
        public String displayDescription() {
            return "检索成长资源库";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("keyword", Map.of("type", "string")),
                    "additionalProperties", false);
        }

        @Override
        public ToolResult execute(AgentToolContext context, JsonObject arguments) {
            return ToolResult.ok(Map.of("count", 1, "items", List.of("数学建模竞赛")));
        }
    }
}

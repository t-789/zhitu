package org.example.lifecomposer.support;

import org.example.lifecomposer.Service.LlmClient;
import org.example.lifecomposer.Service.LlmStreamListener;
import org.example.lifecomposer.dto.LlmRequestDto;
import org.example.lifecomposer.dto.LlmResponseDto;
import org.example.lifecomposer.dto.LlmToolCall;

import java.util.List;

/** Tiny in-memory chat LLMs for controller tests that must not touch a real provider. */
public final class StubChatLlm {

    private StubChatLlm() {
    }

    /** A healthy client that always answers with plain text. */
    public static LlmClient replying(String text) {
        return new Stub(true) {
            @Override
            public LlmResponseDto chat(LlmRequestDto request) {
                LlmResponseDto response = new LlmResponseDto();
                response.setMocked(false);
                response.setFailed(false);
                response.setContent(text);
                return response;
            }
        };
    }

    /** A client the orchestrator treats as down (like a disabled or key-less provider). */
    public static LlmClient unavailable() {
        return new Stub(false) {
            @Override
            public LlmResponseDto chat(LlmRequestDto request) {
                throw new IllegalStateException("must not be called when unavailable");
            }
        };
    }

    /** A client that is up but whose request fails (HTTP error / network). */
    public static LlmClient failing() {
        return new Stub(true) {
            @Override
            public LlmResponseDto chat(LlmRequestDto request) {
                LlmResponseDto response = new LlmResponseDto();
                response.setFailed(true);
                response.setErrorCode("LLM_UNAVAILABLE");
                return response;
            }
        };
    }

    /** A client whose stream delivers some text and then breaks. */
    public static LlmClient streamsThenFails() {
        return new Stub(true) {
            @Override
            public LlmResponseDto chat(LlmRequestDto request) {
                throw new IllegalStateException("streaming only");
            }

            @Override
            public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
                listener.onContentDelta("partial ");
                LlmResponseDto failure = new LlmResponseDto();
                failure.setFailed(true);
                failure.setErrorCode("LLM_UNAVAILABLE");
                listener.onError(failure);
            }
        };
    }

    /** A client that keeps asking for an unknown tool, so the round limit is hit. */
    public static LlmClient loopingToolCalls() {
        return new Stub(true) {
            @Override
            public LlmResponseDto chat(LlmRequestDto request) {
                LlmResponseDto response = new LlmResponseDto();
                response.setMocked(false);
                response.setFailed(false);
                response.setToolCalls(List.of(new LlmToolCall("call-loop", "no_such_tool", "{}")));
                return response;
            }
        };
    }

    private abstract static class Stub implements LlmClient {
        private final boolean available;

        Stub(boolean available) {
            this.available = available;
        }

        @Override
        public void chatStream(LlmRequestDto request, LlmStreamListener listener) {
            LlmResponseDto response = chat(request);
            if (response.isFailed()) {
                listener.onError(response);
            } else {
                listener.onComplete(response);
            }
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String getProvider() {
            return "deepseek";
        }

        @Override
        public String getModel() {
            return "stub";
        }
    }
}

package org.example.lifecomposer.agent;

/** Raised when /api/chat/* cannot reach a real LLM and must not fake a reply. */
public class LlmUnavailableException extends RuntimeException {

    private final String code;

    public LlmUnavailableException(String message) {
        this("LLM_UNAVAILABLE", message);
    }

    public LlmUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Whether the caller should get the chat quota back. An unreachable or failing
     * LLM is a server-side fault, so the user is not charged. Exceeding the tool
     * round limit is driven by the model's behaviour after real calls were made,
     * so it still counts.
     */
    public boolean isRefundable() {
        return !"MAX_TOOL_ROUNDS".equals(code);
    }
}

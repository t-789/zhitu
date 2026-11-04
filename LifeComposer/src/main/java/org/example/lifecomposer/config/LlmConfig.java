package org.example.lifecomposer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-use-case LLM configuration bound from {@code llm.useCases.*} properties.
 * Defaults point to local Ollama with all use cases disabled, so the app
 * starts without cloud API keys and without Ollama running. Configuration
 * only — does not call any LLM.
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
@Getter
@Setter
public class LlmConfig {

    private Map<String, UseCaseConfig> useCases = new HashMap<>();

    /**
     * Resolve a use-case config by name (case-insensitive), returning a safe
     * disabled default for unknown/missing/null use cases. Never returns null.
     */
    public UseCaseConfig resolveOrDefault(String useCaseName) {
        if (useCaseName == null || useCaseName.isBlank()) {
            return UseCaseConfig.defaultConfig();
        }
        return useCases.getOrDefault(useCaseName.toLowerCase(), UseCaseConfig.defaultConfig());
    }

    public UseCaseConfig getUseCase(String name) {
        return resolveOrDefault(name);
    }

    @Getter
    @Setter
    public static class UseCaseConfig {

        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com/v1";
        private String model = "deepseek-flash";

        /**
         * Name of the environment variable holding the API key (e.g.
         * "DEEPSEEK_API_KEY"). Stores the NAME only, never the value.
         * Empty means no auth needed (local Ollama) or deterministic fallback.
         */
        private String apiKeyEnv = "DEEPSEEK_API_KEY";

        /** Disabled by default so startup needs no cloud key or running Ollama. */
        private boolean enabled = false;

        private int timeoutMillis = 30000;

        /**
         * Extra attempts after a connection failure or HTTP 429/5xx, made only
         * before any response body has been consumed. 0 disables retrying.
         */
        private int maxRetries = 2;

        /** First backoff delay; doubles on every further attempt (capped at 5 s). */
        private int retryBaseDelayMillis = 500;

        /** Sampling temperature, or null to let the provider decide. */
        private Double temperature = null;

        public static UseCaseConfig defaultConfig() {
            UseCaseConfig config = new UseCaseConfig();
            config.enabled = false;
            return config;
        }
    }
}

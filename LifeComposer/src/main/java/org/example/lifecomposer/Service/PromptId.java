package org.example.lifecomposer.Service;

/** Versioned prompt identities loaded by {@link PromptCatalog}. */
public enum PromptId {

    CHAT_SYSTEM("chat_system.md"),
    PROFILE_EXTRACTION("profile_extraction.md"),
    DIRECTION_EXPLANATION("direction_explanation.md"),
    PATH_SUGGESTION("path_suggestion.md"),
    COMPETITION_RECOMMENDATION("competition_recommendation.md");

    private final String fileName;

    PromptId(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}

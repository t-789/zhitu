package org.example.lifecomposer.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-15: model output is untrusted. The chat page must not depend on a CDN for the
 * sanitizer, must fail closed when a library is missing, and must pin what it still
 * loads from a CDN with Subresource Integrity.
 */
class ChatPageSafetyTest {

    private static final Path EXTERNAL = Path.of("external");

    private static String chatPage() throws IOException {
        return Files.readString(EXTERNAL.resolve("templates").resolve("chat_test.html"), StandardCharsets.UTF_8);
    }

    @Test
    void markdownAndSanitizerAreServedLocally() throws IOException {
        String html = chatPage();
        assertThat(html).contains("src=\"/vendor/marked.min.js\"").contains("src=\"/vendor/purify.min.js\"");
        assertThat(html).doesNotContain("cdn.jsdelivr.net/npm/marked").doesNotContain("cdn.jsdelivr.net/npm/dompurify");
        assertThat(EXTERNAL.resolve("static/vendor/marked.min.js")).isRegularFile();
        assertThat(EXTERNAL.resolve("static/vendor/purify.min.js")).isRegularFile();
    }

    @Test
    void everyRemoteTagHasIntegrityAndCrossorigin() throws IOException {
        Matcher tags = Pattern.compile("<(?:script|link)\\b[^>]*(?:src|href)=\"https://[^>]*>", Pattern.DOTALL)
                .matcher(chatPage());
        int remote = 0;
        while (tags.find()) {
            remote++;
            assertThat(tags.group())
                    .as("remote tag must be pinned: %s", tags.group())
                    .containsPattern("integrity=\"sha384-[A-Za-z0-9+/=]+\"")
                    .contains("crossorigin=\"anonymous\"");
        }
        assertThat(remote).as("KaTeX css + js").isEqualTo(2);
    }

    @Test
    void renderingFailsClosedWithoutSanitizer() throws IOException {
        String html = chatPage();
        int fn = html.indexOf("function renderAssistantMessage");
        assertThat(fn).isPositive();
        String body = html.substring(fn, html.indexOf("function renderKaTeX", fn));

        // The guard must run before any HTML is produced, and the old "sanitize only if
        // available" branch must be gone.
        int guard = body.indexOf("typeof DOMPurify === \"undefined\"");
        assertThat(guard).as("fail-closed guard").isPositive();
        assertThat(guard).isLessThan(body.indexOf("marked.parse"));
        assertThat(body).contains("return escapeHtml(text)");
        assertThat(body).doesNotContain("typeof DOMPurify !== \"undefined\"");
        assertThat(body).doesNotContain("typeof marked !== \"undefined\"");
    }
}

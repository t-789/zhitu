package org.example.lifecomposer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** P1-16: clickjacking protection and a baseline CSP on every kind of response. */
class SecurityHeadersTest extends BaseControllerTest {

    private static final String UA = "TestClient/1.0";

    private void assertHardened(ResultActions result) throws Exception {
        result.andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        String csp = result.andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).as("Content-Security-Policy").isNotNull()
                .contains("frame-ancestors 'self'")
                .contains("object-src 'none'")
                .contains("base-uri 'self'")
                .contains("form-action 'self'")
                .contains("default-src 'self'");
        assertThat(csp).doesNotContain("*").doesNotContain("trycloudflare");
    }

    @Test
    void publicPageIsNotFrameableByOtherSites() throws Exception {
        assertHardened(mockMvc.perform(get("/front/login").header("User-Agent", UA)));
    }

    @Test
    void apiResponseCarriesTheHeaders() throws Exception {
        assertHardened(mockMvc.perform(get("/api/csrf").header("User-Agent", UA)));
    }

    @Test
    void rejectedRequestsCarryTheHeadersToo() throws Exception {
        // Anonymous access to the admin console is refused; the refusal must also be unframeable.
        assertHardened(mockMvc.perform(get("/admin").header("User-Agent", UA)));
    }

    @Test
    void policyOnlyAllowsTheThirdPartyOriginsThePagesActuallyUse() throws Exception {
        String csp = mockMvc.perform(get("/front/login").header("User-Agent", UA))
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        assertThat(csp)
                .contains("https://cdn.jsdelivr.net")
                .contains("https://fonts.googleapis.com")
                .contains("https://fonts.gstatic.com");
        // script-src must not be opened to anything beyond self, inline (transition) and jsDelivr.
        String scriptSrc = java.util.Arrays.stream(csp.split(";"))
                .map(String::trim).filter(d -> d.startsWith("script-src")).findFirst().orElseThrow();
        assertThat(scriptSrc).isEqualTo("script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net");
    }
}

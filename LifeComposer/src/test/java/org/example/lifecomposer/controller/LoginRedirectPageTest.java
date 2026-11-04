package org.example.lifecomposer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v0.1.4: the login page receives {@code ?redirect=} already sanitised by the controller.
 *
 * <p>落地页通过 {@code <body data-login-redirect="...">} 传给页面，而不是往 JS 里内联字符串 ——
 * Thymeleaf 的 JS 内联会把 {@code /} 转义成 {@code \/}，既难断言也多一层转义风险。</p>
 */
class LoginRedirectPageTest extends BaseControllerTest {

    @Test
    @DisplayName("a same-site redirect is handed to the page")
    void sameSiteRedirectIsRendered() throws Exception {
        mockMvc.perform(get("/front/login").param("redirect", "/front/chat_test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-login-redirect=\"/front/chat_test\"")));
    }

    @Test
    @DisplayName("a protocol-relative redirect falls back to the default page")
    void hostileRedirectIsReplaced() throws Exception {
        mockMvc.perform(get("/front/login").param("redirect", "//evil.example.com/steal"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("evil.example.com"))))
                .andExpect(content().string(containsString("data-login-redirect=\"/front/profile\"")));
    }

    @Test
    @DisplayName("no redirect parameter means the default landing page")
    void defaultLandingPage() throws Exception {
        mockMvc.perform(get("/front/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-login-redirect=\"/front/profile\"")));
    }

    @Test
    @DisplayName("the register page uses the same sanitised value")
    void registerPageUsesSameRule() throws Exception {
        mockMvc.perform(get("/front/register").param("redirect", "https://evil.example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("evil.example.com"))))
                .andExpect(content().string(containsString("data-login-redirect=\"/front/profile\"")));
    }
}

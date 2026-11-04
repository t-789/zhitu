package org.example.lifecomposer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v0.1.3: /api/auth/* account-center contract (envelope, validation, login by account). */
class AuthControllerTest extends BaseControllerTest {

    private static final String PWD = "abc12345";

    private String registerBody(String studentId, String email, String pwd, String confirm) {
        return "{\"studentId\":\"" + studentId + "\",\"realName\":\"张三\",\"email\":\"" + email
                + "\",\"password\":\"" + pwd + "\",\"confirmPassword\":\"" + confirm + "\"}";
    }

    private void register(String studentId, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(registerBody(studentId, email, PWD, PWD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").isNumber());
    }

    private MockHttpSession login(String account) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + PWD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    @DisplayName("register stores student id as username and login works by student id, username and email")
    void registerAndLoginByAnyAccount() throws Exception {
        register("20260001", "Zhang@Example.com");
        assertEquals("20260001", jdbcTemplate.queryForObject(
                "SELECT student_id FROM users WHERE username = '20260001'", String.class));
        login("20260001");
        login("zhang@example.com");
    }

    @Test
    @DisplayName("register validates fields with the envelope")
    void registerValidation() throws Exception {
        String[] bodies = {
                registerBody("ab", "a@b.com", PWD, PWD),
                registerBody("2026 01", "a@b.com", PWD, PWD),
                registerBody("20260002", "not-an-email", PWD, PWD),
                registerBody("20260002", "a@b.com", "abcdefgh", "abcdefgh"),
                registerBody("20260002", "a@b.com", "12345678", "12345678"),
                registerBody("20260002", "a@b.com", PWD, "different1"),
        };
        for (String body : bodies) {
            mockMvc.perform(post("/api/auth/register").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    @DisplayName("duplicate student id or email is a 409")
    void duplicateRejected() throws Exception {
        register("20260003", "dup@example.com");
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(registerBody("20260003", "other@example.com", PWD, PWD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content(registerBody("20260004", "dup@example.com", PWD, PWD)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("unknown account and wrong password are indistinguishable (code 1001)")
    void antiEnumeration() throws Exception {
        register("20260005", "e@example.com");
        String unknown = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"nobody1\",\"password\":\"abc12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andReturn().getResponse().getContentAsString();
        String wrong = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"20260005\",\"password\":\"wrongpass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andReturn().getResponse().getContentAsString();
        assertEquals(unknown, wrong);
    }

    @Test
    @DisplayName("me returns identity only (no college/major) and 401 envelope when anonymous")
    void me() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        register("20260006", "me@example.com");
        MockHttpSession session = login("20260006");
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value("20260006"))
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andExpect(jsonPath("$.data.realName").value("张三"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.college").doesNotExist())
                .andExpect(jsonPath("$.data.major").doesNotExist());
    }

    @Test
    @DisplayName("logout needs CSRF, invalidates the session and later /me is 401")
    void logout() throws Exception {
        register("20260007", "lo@example.com");
        MockHttpSession session = login("20260007");
        mockMvcWithoutCsrf.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("register and login require CSRF; csrf endpoint is public and enveloped")
    void csrf() throws Exception {
        mockMvcWithoutCsrf.perform(post("/api/auth/register").contentType("application/json")
                        .content(registerBody("20260008", "c@example.com", PWD, PWD)))
                .andExpect(status().isForbidden());
        mockMvcWithoutCsrf.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"));
    }

    @Test
    @DisplayName("a banned account gets 403 on login, without leaking the unban time")
    void banned() throws Exception {
        register("20260009", "b@example.com");
        jdbcTemplate.update("UPDATE users SET is_banned = 1, ban_end_time = NULL WHERE username = '20260009'");
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"20260009\",\"password\":\"abc12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("账户已被封禁，请联系管理员"));
    }

    @Test
    @DisplayName("a banned account with a wrong password is indistinguishable from a bad credential")
    void bannedAccountWithWrongPasswordLooksLikeBadCredentials() throws Exception {
        register("20260012", "b3@example.com");
        jdbcTemplate.update("UPDATE users SET is_banned = 1, ban_end_time = NULL WHERE username = '20260012'");

        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"20260012\",\"password\":\"wrong-pass-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));

        // The same answer as an account that does not exist at all.
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"account\":\"20999999\",\"password\":\"wrong-pass-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    @DisplayName("profile studentId follows users.student_id and ignores client input")
    void profileStudentIdIsSnapshot() throws Exception {
        register("20260010", "p@example.com");
        MockHttpSession session = login("20260010");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/profiles/me")
                        .session(session).contentType("application/json")
                        .content("{\"college\":\"计算机学院\",\"studentId\":\"99999999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value("20260010"));
        mockMvc.perform(get("/api/profiles/me").session(session))
                .andExpect(jsonPath("$.studentId").value("20260010"));
    }
}

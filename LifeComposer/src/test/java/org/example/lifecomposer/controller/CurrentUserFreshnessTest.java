package org.example.lifecomposer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-18: {@code /api/users/current} must not serve the copy cached at login, and a
 * role change must take effect for the affected user's existing sessions (their
 * authorities are fixed at login, so the credential generation is bumped).
 */
class CurrentUserFreshnessTest extends BaseControllerTest {

    private static final String UA = "TestClient/1.0";
    private static final String PASSWORD = "testpass123";
    // Probe for "does this session still act as an administrator": the quota reset
    // checks ROLE_ADMIN in the session's authorities only (it does not re-read the
    // database), as does creating a college credit rule. Endpoints that re-check the
    // database would hide a stale role, so they make a poor probe.

    @Test
    @DisplayName("/current reads the account from the database, not the login-time copy")
    void currentReflectsChangesMadeAfterLogin() throws Exception {
        MockHttpSession session = registerAndLogin("fresh_user1", PASSWORD);
        jdbcTemplate.update("UPDATE users SET avatar = ?, is_banned = 1 WHERE username = ?",
                "/avatars/changed.png", "fresh_user1");

        mockMvc.perform(get("/api/users/current").session(session).header("User-Agent", UA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("/avatars/changed.png"))
                .andExpect(jsonPath("$.isBanned").value(true));
    }

    @Test
    @DisplayName("/current is 401 once the account no longer exists")
    void currentIsUnauthorizedForDeletedAccount() throws Exception {
        MockHttpSession session = registerAndLogin("fresh_user2", PASSWORD);
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", "fresh_user2");

        mockMvc.perform(get("/api/users/current").session(session).header("User-Agent", UA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("granting admin invalidates the target's old sessions; a new login gets the role")
    void grantAdminForcesRelogin() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        MockHttpSession oldSession = registerAndLogin("fresh_user3", PASSWORD);
        int id = userId("fresh_user3");
        int before = credentialVersion(id);

        mockMvc.perform(put("/api/users/" + id + "/grant-admin").session(adminSession).header("User-Agent", UA))
                .andExpect(status().isOk());

        assertThat(credentialVersion(id)).isEqualTo(before + 1);
        mockMvc.perform(get("/api/users/current").session(oldSession).header("User-Agent", UA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESSION_EXPIRED"));

        MockHttpSession newSession = loginUser("fresh_user3", PASSWORD);
        probe(newSession, id)
                .andExpect(status().isOk());
        revoke(adminSession, id);
    }

    @Test
    @DisplayName("revoking admin ends the demoted user's admin session instead of leaving ROLE_ADMIN in place")
    void revokeAdminEndsTheOldAdminSession() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        registerUser("fresh_user4", PASSWORD);
        int id = userId("fresh_user4");
        mockMvc.perform(put("/api/users/" + id + "/grant-admin").session(adminSession).header("User-Agent", UA))
                .andExpect(status().isOk());
        MockHttpSession promoted = loginUser("fresh_user4", PASSWORD);
        probe(promoted, id)
                .andExpect(status().isOk());

        revoke(adminSession, id);

        // Without the credential bump the frozen ROLE_ADMIN keeps passing the filter chain.
        probe(promoted, id)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESSION_EXPIRED"));
        MockHttpSession relogin = loginUser("fresh_user4", PASSWORD);
        probe(relogin, id)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("granting a role the user already has does not sign them out")
    void repeatedGrantLeavesSessionsAlone() throws Exception {
        MockHttpSession adminSession = loginAsAdmin();
        registerUser("fresh_user5", PASSWORD);
        int id = userId("fresh_user5");
        mockMvc.perform(put("/api/users/" + id + "/grant-admin").session(adminSession).header("User-Agent", UA))
                .andExpect(status().isOk());
        MockHttpSession promoted = loginUser("fresh_user5", PASSWORD);
        int before = credentialVersion(id);

        mockMvc.perform(put("/api/users/" + id + "/grant-admin").session(adminSession).header("User-Agent", UA))
                .andExpect(status().isOk());

        assertThat(credentialVersion(id)).isEqualTo(before);
        probe(promoted, id)
                .andExpect(status().isOk());
        revoke(adminSession, id);
    }

    private org.springframework.test.web.servlet.ResultActions probe(MockHttpSession session, int id)
            throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/users/admin/chat-quota/reset/" + id).session(session).header("User-Agent", UA));
    }

    private void revoke(MockHttpSession adminSession, int id) throws Exception {
        mockMvc.perform(put("/api/users/" + id + "/revoke-admin").session(adminSession).header("User-Agent", UA))
                .andExpect(status().isOk());
    }

    private int userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Integer.class, username);
    }

    private int credentialVersion(int id) {
        return jdbcTemplate.queryForObject("SELECT credential_version FROM users WHERE id = ?", Integer.class, id);
    }
}

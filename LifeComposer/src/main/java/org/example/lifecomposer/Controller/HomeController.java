package org.example.lifecomposer.Controller;

import org.example.lifecomposer.Security.SafeRedirect;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "home";
    }

    @GetMapping("/login")
    public String login() {
        return "redirect:/front/login";
    }

    /**
     * Login/registration page. {@code ?redirect=} is validated here (see
     * {@link SafeRedirect}) instead of in JavaScript, so the page only ever sees a
     * value that is already known to be a same-site path.
     */
    @GetMapping({"/front/login", "/front/register"})
    public String userAuthentication(
            @RequestParam(name = "redirect", required = false) String redirect, Model model) {
        model.addAttribute("loginRedirect", SafeRedirect.resolve(redirect, SafeRedirect.DEFAULT_AFTER_LOGIN));
        return "zhitu_auth";
    }

    @GetMapping("/adminlogin")
    public String adminLogin() {
        return "login";
    }

    @GetMapping("/admin")
    public String adminMain(Authentication authentication) {
        return adminView(authentication, "admin_main");
    }

    @GetMapping("/admin/user")
    public String userManagement(Authentication authentication) {
        return adminView(authentication, "user_management");
    }

    @GetMapping("/admin/profile")
    public String adminProfiles(Authentication authentication) {
        return adminView(authentication, "admin_profile");
    }

    /** Milestone 7: planning_history + chat_messages debug views. */
    @GetMapping("/admin/planning")
    public String adminPlanning(Authentication authentication) {
        return adminView(authentication, "admin_planning");
    }

    @GetMapping("/admin/chat")
    public String adminChat(Authentication authentication) {
        return adminView(authentication, "admin_chat");
    }

    @GetMapping("/admin/feedback_management")
    public String feedbackManagement(Authentication authentication) {
        return adminView(authentication, "feedback_management");
    }

    @GetMapping("/admin/credit-rules")
    public String adminCreditRules(Authentication authentication) {
        return adminView(authentication, "admin_credit_rules");
    }

    @GetMapping("/admin/credit-activities")
    public String adminCreditActivities(Authentication authentication) {
        return adminView(authentication, "admin_credit_activities");
    }

    @GetMapping("/admin/resources")
    public String adminResources(Authentication authentication) {
        return adminView(authentication, "admin_resources");
    }

    @GetMapping("/admin/rag")
    public String adminRag(Authentication authentication) {
        return adminView(authentication, "admin_rag");
    }

    @GetMapping("/admin/capability-tags")
    public String adminCapabilityTags(Authentication authentication) {
        return adminView(authentication, "admin_capability_tags");
    }

    @GetMapping("/admin/capability-reference")
    public String adminCapabilityReference(Authentication authentication) {
        return adminView(authentication, "admin_capability_reference");
    }

    @GetMapping("/admin/usage")
    public String adminUsage(Authentication authentication) {
        return adminView(authentication, "admin_usage");
    }

    /**
     * Admin pages are already restricted to ROLE_ADMIN by the security filter
     * chain; the controller repeats the check so a misconfiguration can never
     * expose an operator view.
     */
    private String adminView(Authentication authentication, String template) {
        if (isAdmin(authentication)) {
            return template;
        }
        return "redirect:/error/403";
    }

    @GetMapping("/release-notes")
    public String releaseNotes() {
        return "release_notes";
    }

    @GetMapping("/front/chat_test")
    public String chatTest() {
        return "chat_test";
    }

    /** v0.1 M1: formal user profile form (create/view/edit own profile). */
    @GetMapping("/front/profile")
    public String profile() {
        return "profile";
    }

    /**
     * Review follow-up: the only page a forced-change session may open. The page
     * itself is protected by the filter chain ({@code /front/**} requires
     * authentication) and by {@code SessionCredentialGuardFilter}.
     */
    @GetMapping("/front/change-password")
    public String changePassword() {
        return "change_password";
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}

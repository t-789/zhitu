package org.example.lifecomposer.Security;

import org.example.lifecomposer.Repository.UserRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebSecurityConfig {

    /**
     * Response CSP. The only third-party origins the pages use are jsDelivr (KaTeX in
     * the chat page) and Google Fonts (the login/register page); everything else is
     * same-origin.
     *
     * <p>{@code 'unsafe-inline'} on {@code script-src} is a transition measure: the
     * templates still contain inline {@code <script>} blocks and {@code onclick}
     * attributes. Remove it once the front end moves them into static files; until
     * then the policy still blocks foreign scripts, plugins, foreign form targets and
     * framing by other sites ({@code frame-ancestors}).</p>
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com",
            "font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com",
            "img-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    private final AppSecurityProperties securityProperties;
    private final UserRepository userRepository;

    public WebSecurityConfig(AppSecurityProperties securityProperties, UserRepository userRepository) {
        this.securityProperties = securityProperties;
        this.userRepository = userRepository;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Milestone 5: CSRF is required for state-changing requests.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(new XsrfHeaderAliasFilter(), CsrfFilter.class)
                // Review follow-up: credential-generation / forced-password-change
                // guard, evaluated before authorization so a stale session is
                // answered with 401 instead of reaching a controller.
                .addFilterBefore(new SessionCredentialGuardFilter(userRepository),
                        AuthorizationFilter.class)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // Clickjacking: only this site may frame its own pages (the admin console's
                // ban/grant buttons must not be embeddable by other sites).
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/csrf",
                                // Account-center contract: controller answers 401 in the envelope itself.
                                "/api/auth/**",
                                "/api/users/register",
                                "/api/users/login",
                                "/api/feedback/submit",
                                "/api/feedback/system-error",
                                "/api/qa/health"
                        ).permitAll()
                        .requestMatchers(
                                "/api/feedback/all",
                                "/api/feedback/type/**",
                                "/api/feedback/*/resolve",
                                "/api/users/*/grant-admin",
                                "/api/users/*/revoke-admin",
                                "/api/users/*/ban",
                                "/api/users/*/unban",
                                "/api/users/admin/**",
                                "/api/users/all"
                        ).hasRole("ADMIN")
                        // Milestone 7: the whole admin console (API + pages) is
                        // admin-only at the filter-chain level, in addition to the
                        // per-method checks inside the controllers.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .requestMatchers("/front/login", "/front/register").permitAll()
                        .requestMatchers("/api/qa/**").authenticated()
                        // Review follow-up: self-service password change (also the
                        // only call a forced-change session can make).
                        .requestMatchers("/api/users/password").authenticated()
                        .requestMatchers("/api/users/current", "/api/users/logout").authenticated()
                        .requestMatchers("/api/profiles/**").authenticated()
                        .requestMatchers("/api/planning/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated()
                        .requestMatchers("/api/growth-directions/**").authenticated()
                        .requestMatchers("/api/recommendation-feedback/**").authenticated()
                        .requestMatchers("/api/college-credit-rules/**").authenticated()
                        .requestMatchers("/api/credit-activities/**").authenticated()
                        .requestMatchers("/api/resources/**").authenticated()
                        .requestMatchers("/api/rag-chunks/**").authenticated()
                        .requestMatchers("/api/capability-tags/**").authenticated()
                        .requestMatchers("/api/capability-reference/**").authenticated()
                        .requestMatchers("/front/**").authenticated()
                        .anyRequest().permitAll()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/front/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                );

        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite("Lax")
                .secure(securityProperties.isCsrfCookieSecure()));
        return repository;
    }

    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Explicit origins only; never "*" with allowCredentials=true.
        configuration.setAllowedOriginPatterns(List.copyOf(securityProperties.getAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

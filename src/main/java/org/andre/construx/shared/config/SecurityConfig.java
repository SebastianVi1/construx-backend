package org.andre.construx.shared.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserStatus;
import org.andre.construx.modules.identity.service.GoogleLoginHandler;
import org.andre.construx.shared.security.AuthRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain oauthChain(HttpSecurity http, GoogleLoginHandler handler) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/oauth2/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .oauth2Login(o -> o.successHandler(handler).failureHandler(handler))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http,
            @Value("${app.security.rate-limit.max-requests:10}") int rateLimitMax,
            @Value("${app.security.rate-limit.window-seconds:60}") long rateLimitWindowSeconds) throws Exception {
        // Resource Server normally exempts bearer tokens from CSRF. Our bearer token
        // lives in a browser cookie, so explicitly protect every unsafe API request.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        CsrfFilter csrfFilter = new CsrfFilter(csrfTokens);
        csrfFilter.setAccessDeniedHandler(
                (req, res, ex) -> writeProblem(res, HttpStatus.FORBIDDEN, "Access denied", "FORBIDDEN"));
        csrfFilter.setRequireCsrfProtectionMatcher(request -> !java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE")
                .contains(request.getMethod()));
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/register", "/api/auth/verify", "/api/auth/resend",
                        "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/csrf").permitAll()
                .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(csrfFilter, LogoutFilter.class)
                .addFilterBefore(new AuthRateLimitFilter(rateLimitMax, Duration.ofSeconds(rateLimitWindowSeconds),
                        Clock.systemUTC()), CsrfFilter.class)
                .oauth2ResourceServer(o -> o.bearerTokenResolver(cookieBearerTokenResolver()).jwt(j -> {}))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, HttpStatus.UNAUTHORIZED, "Authentication required", "UNAUTHORIZED"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, HttpStatus.FORBIDDEN, "Access denied", "FORBIDDEN")));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse res, HttpStatus status, String title, String code)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"code\":\"" + code + "\"}");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.security.frontend-url}") String frontendUrl) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    BearerTokenResolver cookieBearerTokenResolver() {
        return request -> {
            if (request.getCookies() == null) return null;
            for (Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) return cookie.getValue();
            }
            return null;
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    UserDetailsService userDetailsService(UserAccountRepository users) {
        return email -> {
            UserAccount account = users.findByEmail(UserAccount.normalizeEmail(email))
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
            if (account.getStatus() != UserStatus.ACTIVE || !account.isEmailVerified() || account.getPasswordHash() == null)
                throw new DisabledException("Account unavailable");
            return User.withUsername(account.getEmail()).password(account.getPasswordHash()).authorities("USER").build();
        };
    }
}

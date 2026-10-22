package org.andre.construx.shared.config;

import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserStatus;
import org.andre.construx.modules.identity.service.GoogleLoginHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
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
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        // Resource Server normally exempts bearer tokens from CSRF. Our bearer token
        // lives in a browser cookie, so explicitly protect every unsafe API request.
        CsrfFilter csrfFilter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        csrfFilter.setRequireCsrfProtectionMatcher(request -> !java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE")
                .contains(request.getMethod()));
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/register", "/api/auth/verify", "/api/auth/resend",
                        "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/csrf").permitAll()
                .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(csrfFilter, LogoutFilter.class)
                .oauth2ResourceServer(o -> o.bearerTokenResolver(cookieBearerTokenResolver()).jwt(j -> {}))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    res.getWriter().write("{\"title\":\"Authentication required\",\"status\":401,\"code\":\"UNAUTHORIZED\"}");
                }).accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(HttpStatus.FORBIDDEN.value());
                    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    res.getWriter().write("{\"title\":\"Access denied\",\"status\":403,\"code\":\"FORBIDDEN\"}");
                }));
        return http.build();
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

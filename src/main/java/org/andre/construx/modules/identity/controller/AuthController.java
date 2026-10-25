package org.andre.construx.modules.identity.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.util.Map;
import java.util.UUID;

/** Browser authentication endpoints; bearer JWTs stay in HttpOnly cookies. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    /** A pending Google link must be completed quickly; an abandoned one expires with its session. */
    private static final int LINK_SESSION_SECONDS = 600;

    private final AccountService accounts;
    private final TokenService tokens;
    private final AuthCookies cookies;
    private final AuthenticationManager auth;
    private final UserAccountRepository users;

    public AuthController(AccountService accounts, TokenService tokens, AuthCookies cookies,
            AuthenticationManager auth, UserAccountRepository users) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.cookies = cookies;
        this.auth = auth;
        this.users = users;
    }

    public record Credentials(@Email @NotBlank String email, @NotBlank String password) {}
    public record Registration(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password) {}
    public record EmailInput(@Email @NotBlank String email) {}
    public record VerificationInput(@NotBlank String token) {}
    public record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {}

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody Registration body) {
        accounts.register(body.email(), body.password());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/resend")
    public ResponseEntity<Void> resend(@Valid @RequestBody EmailInput body) {
        accounts.resend(body.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody VerificationInput body) {
        accounts.verify(body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody Credentials body, HttpServletResponse response) {
        String email = UserAccount.normalizeEmail(body.email());
        auth.authenticate(new UsernamePasswordAuthenticationToken(email, body.password()));
        UserAccount user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        cookies.write(response, tokens.start(user));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        cookies.write(response, tokens.refresh(cookie(request, "refresh_token")));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        tokens.logoutByRefresh(cookie(request, "refresh_token"));
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange body,
            Authentication authentication, HttpServletResponse response) {
        accounts.changePassword(currentUserId(authentication),
                body.currentPassword(), body.newPassword());
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, String> me(Authentication authentication) {
        UUID id = currentUserId(authentication);
        UserAccount user = users.findById(id).orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        return Map.of("id", id.toString(), "email", user.getEmail());
    }

    @PostMapping("/google/link")
    public Map<String, String> linkGoogle(Authentication authentication, HttpServletRequest request) {
        UUID id = currentUserId(authentication);
        HttpSession session = request.getSession(true);
        session.setAttribute("linkUserId", id);
        session.setMaxInactiveInterval(LINK_SESSION_SECONDS);
        return Map.of("authorizationUrl", "/oauth2/authorization/google");
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    private String cookie(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }

    private static UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

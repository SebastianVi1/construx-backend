package org.andre.construx.modules.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.andre.construx.modules.identity.model.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/** Converts a verified Google OIDC login into an application session. */
@Component
public class GoogleLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final AccountService accounts;
    private final TokenService tokens;
    private final AuthCookies cookies;
    private final OAuth2AuthorizedClientService authorizedClients;
    private final String frontendUrl;

    public GoogleLoginHandler(AccountService accounts, TokenService tokens, AuthCookies cookies,
            OAuth2AuthorizedClientService authorizedClients,
            @Value("${app.security.frontend-url}") String frontendUrl) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.cookies = cookies;
        this.authorizedClients = authorizedClients;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser oidc))
                throw new IllegalArgumentException("OIDC identity required");
            HttpSession session = request.getSession(false);
            UUID linking = session == null ? null : (UUID) session.getAttribute("linkUserId");
            UserAccount user = accounts.google(oidc.getSubject(), oidc.getEmail(),
                    Boolean.TRUE.equals(oidc.getEmailVerified()), linking);
            cookies.write(response, tokens.start(user));
            if (session != null) session.invalidate();
            response.sendRedirect(frontendUrl + "/auth/complete");
        } catch (RuntimeException ex) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            response.sendRedirect(frontendUrl + "/login?error=google");
        } finally {
            if (authentication instanceof OAuth2AuthenticationToken oauth) {
                authorizedClients.removeAuthorizedClient(oauth.getAuthorizedClientRegistrationId(), oauth.getName());
            }
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        response.sendRedirect(frontendUrl + "/login?error=google");
    }
}

package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.UserAccount;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleLoginHandlerTest {
    @Test
    void successfulExplicitLinkIssuesApplicationCookiesAndEndsOauthSession() throws Exception {
        AccountService accounts = mock(AccountService.class);
        TokenService tokens = mock(TokenService.class);
        AuthCookies cookies = new AuthCookies();
        GoogleLoginHandler handler = new GoogleLoginHandler(accounts, tokens, cookies,
                mock(OAuth2AuthorizedClientService.class), "https://app.example.test");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        UUID userId = UUID.randomUUID();
        var oauthSession = request.getSession(true);
        oauthSession.setAttribute("linkUserId", userId);
        OidcUser oidc = mock(OidcUser.class);
        when(oidc.getSubject()).thenReturn("google-subject");
        when(oidc.getEmail()).thenReturn("person@example.test");
        when(oidc.getEmailVerified()).thenReturn(true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oidc);
        UserAccount user = UserAccount.google("person@example.test");
        when(accounts.google("google-subject", "person@example.test", true, userId)).thenReturn(user);
        when(tokens.start(user)).thenReturn(new TokenService.Tokens("access", "refresh"));

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://app.example.test/auth/complete", response.getRedirectedUrl());
        assertEquals(2, response.getHeaders("Set-Cookie").size());
        assertThrows(IllegalStateException.class, () -> oauthSession.getAttribute("linkUserId"));
    }

    @Test
    void rejectedGoogleIdentityDoesNotIssueCookies() throws Exception {
        AccountService accounts = mock(AccountService.class);
        GoogleLoginHandler handler = new GoogleLoginHandler(accounts, mock(TokenService.class),
                new AuthCookies(), mock(OAuth2AuthorizedClientService.class), "https://app.example.test");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OidcUser oidc = mock(OidcUser.class);
        when(oidc.getSubject()).thenReturn("google-subject");
        when(oidc.getEmail()).thenReturn("person@example.test");
        when(oidc.getEmailVerified()).thenReturn(true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oidc);
        when(accounts.google("google-subject", "person@example.test", true, null))
                .thenThrow(new BadCredentialsException("Link first"));

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://app.example.test/login?error=google", response.getRedirectedUrl());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }

    @Test
    void rejectedGoogleIdentityIsLoggedForOperators() throws Exception {
        AccountService accounts = mock(AccountService.class);
        GoogleLoginHandler handler = new GoogleLoginHandler(accounts, mock(TokenService.class),
                new AuthCookies(), mock(OAuth2AuthorizedClientService.class), "https://app.example.test");
        OidcUser oidc = mock(OidcUser.class);
        when(oidc.getSubject()).thenReturn("google-subject");
        when(oidc.getEmail()).thenReturn("person@example.test");
        when(oidc.getEmailVerified()).thenReturn(true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oidc);
        when(accounts.google("google-subject", "person@example.test", true, null))
                .thenThrow(new BadCredentialsException("Link first"));
        Logger logger = (Logger) LoggerFactory.getLogger(GoogleLoginHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(), authentication);
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
    }
}

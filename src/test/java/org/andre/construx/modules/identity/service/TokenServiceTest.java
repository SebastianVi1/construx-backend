package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.AuthSession;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.AuthSessionRepository;
import org.andre.construx.shared.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenServiceTest {
    private AuthSessionRepository repository;
    private TokenService tokens;
    private JwtDecoder decoder;
    private UserAccount user;
    private AtomicReference<AuthSession> saved;

    @BeforeEach
    void setUp() {
        repository = mock(AuthSessionRepository.class);
        JwtConfig config = new JwtConfig();
        SecretKey key = config.jwtKey(Base64.getEncoder().encodeToString(new byte[32]));
        tokens = new TokenService(repository, config.jwtEncoder(key), "construx-test", "test-api");
        decoder = config.jwtDecoder(key, repository, "construx-test", "test-api");
        user = new UserAccount("person@example.com", "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.verifyEmail();
        saved = new AtomicReference<>();
        when(repository.save(any(AuthSession.class))).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });
        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.findWithLockById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
    }

    @Test
    void jwtWorksUntilSessionIsRevoked() {
        TokenService.Tokens issued = tokens.start(user);
        assertEquals(user.getId().toString(), decoder.decode(issued.access()).getSubject());
        tokens.logoutByRefresh(issued.refresh());
        assertThrows(Exception.class, () -> decoder.decode(issued.access()));
    }

    @Test
    void refreshRotatesSecretAndReplayRevokesSession() {
        TokenService.Tokens first = tokens.start(user);
        TokenService.Tokens second = tokens.refresh(first.refresh());
        assertNotEquals(first.refresh(), second.refresh());
        assertThrows(BadCredentialsException.class, () -> tokens.refresh(first.refresh()));
        assertFalse(saved.get().activeAt(Instant.now()));
    }

    @Test
    void malformedRefreshIsRejected() {
        assertThrows(BadCredentialsException.class, () -> tokens.refresh("garbage"));
    }

    @Test
    void disablingAccountRejectsExistingJwt() {
        TokenService.Tokens issued = tokens.start(user);
        user.disable();
        assertThrows(Exception.class, () -> decoder.decode(issued.access()));
        assertThrows(BadCredentialsException.class, () -> tokens.refresh(issued.refresh()));
    }
}

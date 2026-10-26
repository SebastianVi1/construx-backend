package org.andre.construx.modules.identity.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.andre.construx.modules.identity.model.PasswordReset;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.PasswordResetRepository;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordResetServiceTest {
    private static final String EMAIL = "person@example.test";

    private UserAccountRepository users;
    private PasswordResetRepository resets;
    private TokenService tokens;
    private AuthMailer mailer;
    private PasswordEncoder encoder;
    private PasswordResetService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        resets = mock(PasswordResetRepository.class);
        tokens = mock(TokenService.class);
        mailer = mock(AuthMailer.class);
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new PasswordResetService(users, resets, encoder, tokens, mailer, "https://app.example.test");
        user = new UserAccount(EMAIL, encoder.encode("old-password-123"));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(resets.save(any(PasswordReset.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String sentToken() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(user), eq("Reset your Construx password"), text.capture());
        return text.getValue().split("token=")[1].split("\\s")[0];
    }

    private PasswordReset openToken(String raw) {
        PasswordReset reset = new PasswordReset(user, TokenService.sha256(raw), Instant.now().plus(Duration.ofMinutes(60)));
        ReflectionTestUtils.setField(reset, "id", UUID.randomUUID());
        when(resets.findByTokenHash(TokenService.sha256(raw))).thenReturn(Optional.of(reset));
        when(resets.consume(eq(reset.getId()), any(Instant.class))).thenReturn(1);
        return reset;
    }

    @Test
    void unknownEmailSendsNothingAndDoesNotThrow() {
        when(users.findByEmail("nobody@example.test")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.requestReset("nobody@example.test"));
        verifyNoInteractions(mailer);
        verify(resets, never()).save(any());
    }

    @Test
    void unverifiedActiveAccountReceivesTheMailWithTheDocumentedWording() {
        service.requestReset(" Person@Example.test ");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(user), eq("Reset your Construx password"), text.capture());
        assertTrue(text.getValue().startsWith("We received a request to reset the password for this email address. "
                + "Open this link within 60 minutes to choose a new password: https://app.example.test/reset-password?token="));
        assertTrue(text.getValue().endsWith("\n\nIf you did not request this, ignore this email. Your password will not change."));
    }

    @Test
    void disabledAccountGetsNothing() {
        user.disable();
        service.requestReset(EMAIL);
        verifyNoInteractions(mailer);
        verify(resets, never()).save(any());
    }

    @Test
    void secondRequestWithinTheCooldownSendsNothing() {
        when(resets.existsOpenCreatedAfter(eq(user), any(Instant.class), any(Instant.class))).thenReturn(true);
        service.requestReset(EMAIL);
        verifyNoInteractions(mailer);
        verify(resets, never()).save(any());
        verify(resets, never()).invalidateOpenFor(any(), any());
    }

    @Test
    void requestAfterTheCooldownInvalidatesOlderTokensThenStoresAndMails() {
        when(resets.existsOpenCreatedAfter(eq(user), any(Instant.class), any(Instant.class))).thenReturn(false);
        service.requestReset(EMAIL);
        var order = inOrder(resets, mailer);
        order.verify(resets).invalidateOpenFor(eq(user), any(Instant.class));
        order.verify(resets).save(any(PasswordReset.class));
        order.verify(mailer).send(eq(user), anyString(), anyString());
    }

    @Test
    void cooldownCutoffIsSixtySecondsBack() {
        service.requestReset(EMAIL);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(resets).existsOpenCreatedAfter(eq(user), now.capture(), cutoff.capture());
        assertEquals(Duration.ofSeconds(60), Duration.between(cutoff.getValue(), now.getValue()));
    }

    @Test
    void tokenLivesSixtyMinutes() {
        service.requestReset(EMAIL);
        ArgumentCaptor<PasswordReset> saved = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resets).save(saved.capture());
        long seconds = Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getExpiresAt()).toSeconds();
        assertTrue(seconds >= 3599 && seconds <= 3600, "ttl was " + seconds + "s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mailFailureDoesNotThrowAndTheTokenIsStillStored() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        PasswordResetService withRealMailer = new PasswordResetService(users, resets, encoder, tokens,
                new AuthMailer(provider, "noreply@example.test"), "https://app.example.test");
        assertDoesNotThrow(() -> withRealMailer.requestReset(EMAIL));
        verify(resets).save(any(PasswordReset.class));
    }

    @Test
    void onlyTheHashIsStoredNeverTheRawToken() {
        service.requestReset(EMAIL);
        String raw = sentToken();
        ArgumentCaptor<PasswordReset> saved = ArgumentCaptor.forClass(PasswordReset.class);
        verify(resets).save(saved.capture());
        assertEquals(TokenService.sha256(raw), saved.getValue().getTokenHash());
        assertNotEquals(raw, saved.getValue().getTokenHash());
    }

    @Test
    void validTokenChangesPasswordVerifiesEmailRevokesSessionsAndInvalidatesOthers() {
        openToken("raw-token");
        service.reset("raw-token", "brand-new-password");
        assertTrue(encoder.matches("brand-new-password", user.getPasswordHash()));
        assertTrue(user.getPasswordHash().startsWith("{bcrypt}"));
        assertTrue(user.isEmailVerified());
        verify(resets).invalidateOpenFor(eq(user), any(Instant.class));
        verify(tokens).revokeAll(user.getId());
    }

    @Test
    void reusedTokenFailsBecauseTheAtomicConsumeReturnsZero() {
        PasswordReset reset = openToken("raw-token");
        when(resets.consume(eq(reset.getId()), any(Instant.class))).thenReturn(0);
        assertThrows(BadCredentialsException.class, () -> service.reset("raw-token", "brand-new-password"));
        assertTrue(encoder.matches("old-password-123", user.getPasswordHash()));
        verify(tokens, never()).revokeAll(any());
    }

    @Test
    void expiredTokenFails() {
        PasswordReset reset = openToken("raw-token");
        when(resets.consume(eq(reset.getId()), any(Instant.class))).thenReturn(0);
        ReflectionTestUtils.setField(reset, "expiresAt", Instant.now().minusSeconds(1));
        assertThrows(BadCredentialsException.class, () -> service.reset("raw-token", "brand-new-password"));
        assertFalse(user.isEmailVerified());
    }

    @Test
    void unknownTokenFails() {
        when(resets.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class, () -> service.reset("nope", "brand-new-password"));
        verify(tokens, never()).revokeAll(any());
    }

    @Test
    void disabledAccountFailsAndTheTokenIsNotConsumed() {
        PasswordReset reset = openToken("raw-token");
        user.disable();
        assertThrows(BadCredentialsException.class, () -> service.reset("raw-token", "brand-new-password"));
        verify(resets, never()).consume(eq(reset.getId()), any(Instant.class));
        assertTrue(encoder.matches("old-password-123", user.getPasswordHash()));
    }
}

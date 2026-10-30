package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.*;
import org.andre.construx.modules.identity.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private UserAccountRepository users;
    private EmailVerificationRepository proofs;
    private GoogleIdentityRepository google;
    private UserProfileRepository profiles;
    private JavaMailSender sender;
    private AccountService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        proofs = mock(EmailVerificationRepository.class);
        google = mock(GoogleIdentityRepository.class);
        profiles = mock(UserProfileRepository.class);
        sender = mock(JavaMailSender.class);
        service = new AccountService(users, proofs, google, profiles,
                PasswordEncoderFactories.createDelegatingPasswordEncoder(), mock(TokenService.class),
                mailerWith(sender), "https://app.example.test");
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(proofs.save(any(EmailVerification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private static AuthMailer mailerWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new AuthMailer(provider, "noreply@example.test");
    }

    @Test
    void registrationHashesPasswordAndSendsSingleUseLink() {
        service.register(" Person@Example.com ", "long-password-here");
        var userCaptor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(userCaptor.capture());
        assertEquals("person@example.com", userCaptor.getValue().getEmail());
        assertTrue(userCaptor.getValue().getPasswordHash().startsWith("{bcrypt}"));
        assertFalse(userCaptor.getValue().isEmailVerified());
        verify(proofs).save(any(EmailVerification.class));
        verify(sender).send(any(SimpleMailMessage.class));
    }

    @Test
    void registrationStoresTheFullNameInTheProfile() {
        service.register("person@example.com", "long-password-here", "  Ana López ");
        var profileCaptor = org.mockito.ArgumentCaptor.forClass(UserProfile.class);
        verify(profiles).save(profileCaptor.capture());
        assertEquals("Ana López", profileCaptor.getValue().getFullName());
        assertNull(profileCaptor.getValue().getPhoneNumber());
    }

    @Test
    void registrationWithoutNameCreatesNoProfile() {
        service.register("person@example.com", "long-password-here", " ");
        verify(profiles, never()).save(any());
    }

    @Test
    void resendInvalidatesPreviouslyIssuedLinks() {
        UserAccount pending = new UserAccount("person@example.com", "hash");
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(pending));
        service.resend("person@example.com");
        var order = inOrder(proofs, sender);
        order.verify(proofs).invalidateOpenFor(eq(pending), any(java.time.Instant.class));
        order.verify(proofs).save(any(EmailVerification.class));
        order.verify(sender).send(any(SimpleMailMessage.class));
    }

    @Test
    void registrationSucceedsWhenTheMailServerFails() {
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> service.register("person@example.com", "long-password-here"));
        verify(users).save(any(UserAccount.class));
    }

    @Test
    void registrationSucceedsWhenNoMailSenderIsConfigured() {
        AccountService withoutMail = new AccountService(users, proofs, google, profiles,
                PasswordEncoderFactories.createDelegatingPasswordEncoder(), mock(TokenService.class),
                mailerWith(null), "https://app.example.test");
        assertDoesNotThrow(() -> withoutMail.register("person@example.com", "long-password-here"));
    }

    @Test
    void matchingGoogleEmailDoesNotAutomaticallyLink() {
        when(users.findByEmail("person@example.com"))
                .thenReturn(Optional.of(new UserAccount("person@example.com", "hash")));
        assertThrows(BadCredentialsException.class,
                () -> service.google("google-subject", "person@example.com", true, null));
        verify(google, never()).save(any());
    }

    @Test
    void explicitLinkRequiresVerifiedMatchingEmail() {
        UserAccount user = new UserAccount("person@example.com", "hash");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(user));
        assertThrows(BadCredentialsException.class,
                () -> service.google("google-subject", "person@example.com", true, id));
        user.verifyEmail();
        assertSame(user, service.google("google-subject", "person@example.com", true, id));
        verify(google).save(any(GoogleIdentity.class));
    }
}

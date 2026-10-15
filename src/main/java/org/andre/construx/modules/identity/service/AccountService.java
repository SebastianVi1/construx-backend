package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.*;
import org.andre.construx.modules.identity.repository.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** Account creation and provider linking are separate from organization membership. */
@Service
public class AccountService {
    private final UserAccountRepository users;
    private final EmailVerificationRepository verifications;
    private final GoogleIdentityRepository google;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final ObjectProvider<JavaMailSender> mail;
    private final String frontendUrl;
    private final String mailFrom;
    private final SecureRandom random = new SecureRandom();

    public AccountService(UserAccountRepository users, EmailVerificationRepository verifications,
            GoogleIdentityRepository google, PasswordEncoder passwords, TokenService tokens,
            ObjectProvider<JavaMailSender> mail,
            @Value("${app.security.frontend-url}") String frontendUrl,
            @Value("${app.security.mail-from}") String mailFrom) {
        this.users = users;
        this.verifications = verifications;
        this.google = google;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mail = mail;
        this.frontendUrl = frontendUrl;
        this.mailFrom = mailFrom;
    }

    @Transactional
    public void register(String email, String password) {
        String normalized = UserAccount.normalizeEmail(email);
        if (users.findByEmail(normalized).isPresent()) return;
        UserAccount user = users.save(new UserAccount(normalized, passwords.encode(password)));
        sendVerification(user);
    }

    @Transactional
    public void resend(String email) {
        users.findByEmail(UserAccount.normalizeEmail(email))
                .filter(u -> !u.isEmailVerified()).ifPresent(this::sendVerification);
    }

    @Transactional
    public void verify(String token) {
        EmailVerification proof = verifications.findByTokenHash(TokenService.sha256(token))
                .orElseThrow(() -> new BadCredentialsException("Invalid verification link"));
        if (!proof.usableAt(Instant.now())) throw new BadCredentialsException("Invalid verification link");
        proof.getUser().verifyEmail();
        proof.use(Instant.now());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
        if (user.getPasswordHash() == null || !passwords.matches(currentPassword, user.getPasswordHash()))
            throw new BadCredentialsException("Invalid credentials");
        user.changePassword(passwords.encode(newPassword));
        tokens.revokeAll(userId);
    }

    @Transactional
    public UserAccount google(String subject, String email, boolean verified, UUID linkingUserId) {
        if (!verified || subject == null || subject.isBlank() || email == null)
            throw new BadCredentialsException("Google identity unavailable");
        String normalized = UserAccount.normalizeEmail(email);
        var existing = google.findBySubject(subject);
        if (existing.isPresent()) {
            UserAccount user = existing.get().getUser();
            if (linkingUserId != null && !user.getId().equals(linkingUserId))
                throw new BadCredentialsException("Google account already linked");
            return user;
        }
        UserAccount user;
        if (linkingUserId != null) {
            user = users.findById(linkingUserId)
                    .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
            if (!user.isEmailVerified() || !user.getEmail().equals(normalized))
                throw new BadCredentialsException("Google email must match verified account email");
        } else {
            if (users.findByEmail(normalized).isPresent())
                throw new BadCredentialsException("Sign in and link Google first");
            user = users.save(UserAccount.google(normalized));
        }
        google.save(new GoogleIdentity(subject, user));
        return user;
    }

    private void sendVerification(UserAccount user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        verifications.save(new EmailVerification(user, TokenService.sha256(token),
                Instant.now().plus(24, ChronoUnit.HOURS)));
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("Mail sender is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject("Verify your Construx email");
        message.setText("Open this link to verify your email: " + frontendUrl + "/verify-email?token=" + token);
        sender.send(message);
    }
}

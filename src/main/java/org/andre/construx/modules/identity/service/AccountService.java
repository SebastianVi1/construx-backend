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

/**
 * Coordinates account registration, email verification, password changes, and
 * external identity linking.
 *
 * <p>Account creation and provider linking are separate from organization
 * membership. Email addresses are normalized before they are used for account
 * lookups, and password changes revoke all existing access tokens.</p>
 */
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

    /**
     * Creates a password-based account and sends an email verification link.
     *
     * <p>Registration is idempotent for an existing email address: no new
     * account or verification message is created when the address is already
     * registered.</p>
     *
     * @param email the email address for the new account
     * @param password the raw password to encode and store
     */
    @Transactional
    public void register(String email, String password) {
        String normalized = UserAccount.normalizeEmail(email);
        if (users.findByEmail(normalized).isPresent()) return;
        UserAccount user = users.save(new UserAccount(normalized, passwords.encode(password)));
        sendVerification(user);
    }

    /**
     * Sends a new verification link when the account exists and is not yet
     * verified.
     *
     * <p>Unknown addresses and already-verified accounts are intentionally
     * ignored so this operation does not disclose account state.</p>
     *
     * @param email the email address associated with the account
     */
    @Transactional
    public void resend(String email) {
        users.findByEmail(UserAccount.normalizeEmail(email))
                .filter(u -> !u.isEmailVerified()).ifPresent(this::sendVerification);
    }

    /**
     * Validates a verification token, marks its account email as verified, and
     * consumes the token.
     *
     * @param token the raw token received from the verification link
     * @throws BadCredentialsException if the token is unknown, expired, or
     *         already consumed
     */
    @Transactional
    public void verify(String token) {
        EmailVerification proof = verifications.findByTokenHash(TokenService.sha256(token))
                .orElseThrow(() -> new BadCredentialsException("Invalid verification link"));
        if (!proof.usableAt(Instant.now())) throw new BadCredentialsException("Invalid verification link");
        proof.getUser().verifyEmail();
        proof.use(Instant.now());
    }

    /**
     * Replaces a user's password after validating the current password and
     * revokes all tokens issued for the account.
     *
     * @param userId the account whose password is being changed
     * @param currentPassword the user's current raw password
     * @param newPassword the replacement raw password
     * @throws BadCredentialsException if the account is unavailable or the
     *         current password is invalid
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
        if (user.getPasswordHash() == null || !passwords.matches(currentPassword, user.getPasswordHash()))
            throw new BadCredentialsException("Invalid credentials");
        user.changePassword(passwords.encode(newPassword));
        tokens.revokeAll(userId);
    }

    /**
     * Finds or creates the account represented by a verified Google identity.
     *
     * <p>When {@code linkingUserId} is provided, the Google email must match
     * that account's verified email. Without it, an account is created only
     * when no account already uses the Google email. An existing Google
     * identity is returned unless it belongs to a different account than the
     * requested linking account.</p>
     *
     * @param subject the stable Google identity subject
     * @param email the email returned by Google
     * @param verified whether Google verified the email address
     * @param linkingUserId the existing account to link, or {@code null} to
     *        sign up with Google
     * @return the account associated with the Google identity
     * @throws BadCredentialsException if the identity is invalid, already
     *         linked elsewhere, or cannot be linked to the requested account
     */
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

    /**
     * Generates, stores, and sends a single-use verification token.
     *
     * <p>Only the token hash is persisted. The raw token is included in the
     * verification URL sent to the account email and expires after 24 hours.</p>
     *
     * @param user the account receiving the verification message
     * @throws IllegalStateException if no mail sender is configured
     */
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

package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.PasswordReset;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.PasswordResetRepository;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Password recovery by emailed single-use link.
 *
 * <p>Only the SHA-256 of the token is stored. Requests never reveal whether an address is
 * registered, and a successful reset revokes every session of the account.</p>
 */
@Service
public class PasswordResetService {
    static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(60);

    private final UserAccountRepository users;
    private final PasswordResetRepository resets;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthMailer mailer;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserAccountRepository users, PasswordResetRepository resets,
            PasswordEncoder passwords, TokenService tokens, AuthMailer mailer,
            @Value("${app.security.frontend-url}") String frontendUrl) {
        this.users = users;
        this.resets = resets;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Mails a reset link when an enabled account exists and no link was issued in the last minute.
     * Unknown, disabled and throttled requests are silently ignored so the caller learns nothing.
     */
    @Transactional
    public void requestReset(String email) {
        users.findByEmail(UserAccount.normalizeEmail(email)).filter(UserAccount::canRecoverPassword)
                .ifPresent(this::issueLink);
    }

    /**
     * Sets a new password with a valid token, verifies the email, and revokes all sessions.
     *
     * @throws BadCredentialsException if the token is unknown, expired, used, superseded, or the
     *         account is disabled; the cause is deliberately not distinguished
     */
    @Transactional
    public void reset(String token, String newPassword) {
        PasswordReset proof = resets.findByTokenHash(TokenService.sha256(token))
                .orElseThrow(PasswordResetService::invalidLink);
        UserAccount user = proof.getUser();
        if (!user.canRecoverPassword()) throw invalidLink();
        Instant now = Instant.now();
        if (resets.consume(proof.getId(), now) == 0) throw invalidLink();
        user.resetPassword(passwords.encode(newPassword));
        resets.invalidateOpenFor(user, now);
        tokens.revokeAll(user.getId());
    }

    private void issueLink(UserAccount user) {
        Instant now = Instant.now();
        if (resets.existsOpenCreatedAfter(user, now, now.minus(REQUEST_COOLDOWN))) return;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        resets.invalidateOpenFor(user, now);
        resets.save(new PasswordReset(user, TokenService.sha256(token), now.plus(TOKEN_TTL)));
        mailer.send(user, "Reset your Construx password",
                "We received a request to reset the password for this email address. "
                        + "Open this link within 60 minutes to choose a new password: "
                        + frontendUrl + "/reset-password?token=" + token
                        + "\n\nIf you did not request this, ignore this email. Your password will not change.");
    }

    private static BadCredentialsException invalidLink() {
        return new BadCredentialsException("Invalid reset link");
    }
}

package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.repository.AuthSessionRepository;
import org.andre.construx.modules.identity.repository.EmailVerificationRepository;
import org.andre.construx.modules.identity.repository.PasswordResetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Daily removal of sessions, verification links and password reset links that expired or were used long ago. */
@Component
public class AuthDataCleanup {
    private static final Logger log = LoggerFactory.getLogger(AuthDataCleanup.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final AuthSessionRepository sessions;
    private final EmailVerificationRepository verifications;
    private final PasswordResetRepository passwordResets;

    public AuthDataCleanup(AuthSessionRepository sessions, EmailVerificationRepository verifications,
            PasswordResetRepository passwordResets) {
        this.sessions = sessions;
        this.verifications = verifications;
        this.passwordResets = passwordResets;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(RETENTION);
        log.info("Removed {} dead sessions, {} dead verification links and {} dead password reset links",
                sessions.deleteDeadBefore(cutoff), verifications.deleteDeadBefore(cutoff),
                passwordResets.deleteDeadBefore(cutoff));
    }
}

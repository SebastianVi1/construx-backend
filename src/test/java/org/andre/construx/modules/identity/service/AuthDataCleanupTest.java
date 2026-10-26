package org.andre.construx.modules.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.andre.construx.modules.identity.model.AuthSession;
import org.andre.construx.modules.identity.model.EmailVerification;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.AuthSessionRepository;
import org.andre.construx.modules.identity.repository.EmailVerificationRepository;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthDataCleanupTest {

    @Autowired AuthDataCleanup cleanup;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired EmailVerificationRepository verifications;

    @Test
    void purgeRemovesOnlyLongDeadSessionsAndLinks() {
        UserAccount user = users.save(new UserAccount("cleanup-" + UUID.randomUUID() + "@example.test", "hash"));
        Instant now = Instant.now();
        AuthSession active = sessions.save(new AuthSession(user, "a", now.plus(Duration.ofDays(1))));
        AuthSession expired = sessions.save(new AuthSession(user, "b", now.minus(Duration.ofDays(30))));
        AuthSession revokedLongAgo = new AuthSession(user, "c", now.plus(Duration.ofDays(1)));
        revokedLongAgo.revoke(now.minus(Duration.ofDays(30)));
        sessions.save(revokedLongAgo);
        AuthSession revokedJustNow = new AuthSession(user, "d", now.plus(Duration.ofDays(1)));
        revokedJustNow.revoke(now);
        sessions.save(revokedJustNow);
        EmailVerification staleLink = verifications.save(
                new EmailVerification(user, "stale-" + UUID.randomUUID(), now.minus(Duration.ofDays(30))));
        EmailVerification openLink = verifications.save(
                new EmailVerification(user, "open-" + UUID.randomUUID(), now.plus(Duration.ofHours(1))));

        cleanup.purge();

        assertTrue(sessions.existsById(active.getId()));
        assertTrue(sessions.existsById(revokedJustNow.getId()));
        assertEquals(false, sessions.existsById(expired.getId()));
        assertEquals(false, sessions.existsById(revokedLongAgo.getId()));
        assertEquals(false, verifications.existsById(staleLink.getId()));
        assertTrue(verifications.existsById(openLink.getId()));
    }

    @Test
    void schedulingIsEnabledSoThePurgeActuallyRuns() {
        assertTrue(context.getBeanNamesForType(
                org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).length > 0);
    }
}

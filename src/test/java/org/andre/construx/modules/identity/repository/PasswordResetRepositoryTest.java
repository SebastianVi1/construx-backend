package org.andre.construx.modules.identity.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.andre.construx.modules.identity.model.PasswordReset;
import org.andre.construx.modules.identity.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PasswordResetRepositoryTest {
    @Autowired PasswordResetRepository resets;
    @Autowired UserAccountRepository users;

    private UserAccount user() {
        return users.save(new UserAccount("reset-" + UUID.randomUUID() + "@example.test", "hash"));
    }

    private PasswordReset token(UserAccount user, Instant expiresAt) {
        return resets.save(new PasswordReset(user, "t-" + UUID.randomUUID(), expiresAt));
    }

    @Test
    void consumeSucceedsOnceThenReturnsZero() {
        Instant now = Instant.now();
        PasswordReset reset = token(user(), now.plus(Duration.ofMinutes(60)));
        assertEquals(1, resets.consume(reset.getId(), now));
        assertEquals(0, resets.consume(reset.getId(), now));
    }

    @Test
    void consumeReturnsZeroForAnExpiredRow() {
        Instant now = Instant.now();
        PasswordReset reset = token(user(), now.minusSeconds(1));
        assertEquals(0, resets.consume(reset.getId(), now));
    }

    @Test
    void invalidateOpenForOnlyAffectsThatUsersOpenTokens() {
        Instant now = Instant.now();
        UserAccount owner = user();
        PasswordReset mine = token(owner, now.plus(Duration.ofMinutes(60)));
        PasswordReset other = token(user(), now.plus(Duration.ofMinutes(60)));
        resets.invalidateOpenFor(owner, now);
        assertEquals(0, resets.consume(mine.getId(), now));
        assertEquals(1, resets.consume(other.getId(), now));
    }

    @Test
    void existsOpenCreatedAfterHonoursCutoffUseAndExpiry() {
        Instant now = Instant.now();
        UserAccount owner = user();
        PasswordReset reset = token(owner, now.plus(Duration.ofMinutes(60)));
        assertTrue(resets.existsOpenCreatedAfter(owner, now, now.minusSeconds(60)));
        assertFalse(resets.existsOpenCreatedAfter(owner, now, now.plusSeconds(60)));
        resets.consume(reset.getId(), now);
        assertFalse(resets.existsOpenCreatedAfter(owner, now, now.minusSeconds(60)));
        UserAccount expiredOwner = user();
        token(expiredOwner, now.minusSeconds(1));
        assertFalse(resets.existsOpenCreatedAfter(expiredOwner, now, now.minusSeconds(60)));
    }

    @Test
    void deleteDeadBeforeRemovesOnlyOldExpiredOrUsedRows() {
        Instant now = Instant.now();
        UserAccount owner = user();
        Instant cutoff = now.minus(Duration.ofDays(7));
        PasswordReset stale = token(owner, now.minus(Duration.ofDays(30)));
        PasswordReset usedLongAgo = token(owner, now.plus(Duration.ofDays(1)));
        usedLongAgo.use(now.minus(Duration.ofDays(30)));
        PasswordReset open = token(owner, now.plus(Duration.ofMinutes(60)));
        resets.flush();
        assertEquals(2, resets.deleteDeadBefore(cutoff));
        assertFalse(resets.existsById(stale.getId()));
        assertFalse(resets.existsById(usedLongAgo.getId()));
        assertTrue(resets.existsById(open.getId()));
    }
}

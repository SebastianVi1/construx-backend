package org.andre.construx.modules.identity.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PasswordResetTest {
    private final UserAccount user = new UserAccount("person@example.test", "hash");

    @Test
    void unusedAndUnexpiredTokenIsUsable() {
        Instant now = Instant.now();
        PasswordReset reset = new PasswordReset(user, "h", now.plus(Duration.ofMinutes(60)));
        assertTrue(reset.usableAt(now));
        assertNotNull(reset.getCreatedAt());
    }

    @Test
    void usedTokenIsNotUsable() {
        Instant now = Instant.now();
        PasswordReset reset = new PasswordReset(user, "h", now.plus(Duration.ofMinutes(60)));
        reset.use(now);
        assertEquals(now, reset.getUsedAt());
        assertFalse(reset.usableAt(now));
    }

    @Test
    void expiredTokenIsNotUsable() {
        Instant now = Instant.now();
        assertFalse(new PasswordReset(user, "h", now.minusSeconds(1)).usableAt(now));
    }
}

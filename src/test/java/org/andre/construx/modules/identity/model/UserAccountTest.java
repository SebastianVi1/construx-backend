package org.andre.construx.modules.identity.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserAccountTest {

    @Test
    void unverifiedAccountIsNotActive() {
        assertFalse(new UserAccount("person@example.test", "hash").isActive());
    }

    @Test
    void verifiedEnabledAccountIsActive() {
        UserAccount user = new UserAccount("person@example.test", "hash");
        user.verifyEmail();
        assertTrue(user.isActive());
    }

    @Test
    void disabledAccountIsNotActiveEvenWhenVerified() {
        UserAccount user = new UserAccount("person@example.test", "hash");
        user.verifyEmail();
        user.disable();
        assertFalse(user.isActive());
    }
}

package org.andre.construx.modules.identity.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void resetPasswordSetsTheHashAndVerifiesTheEmail() {
        UserAccount user = new UserAccount("person@example.test", "old");
        user.resetPassword("new-hash");
        assertEquals("new-hash", user.getPasswordHash());
        assertTrue(user.isEmailVerified());
    }

    @Test
    void resetPasswordRejectsBlankOrNullHash() {
        UserAccount user = new UserAccount("person@example.test", "old");
        assertThrows(IllegalArgumentException.class, () -> user.resetPassword(" "));
        assertThrows(IllegalArgumentException.class, () -> user.resetPassword(null));
        assertEquals("old", user.getPasswordHash());
    }

    @Test
    void onlyEnabledAccountsCanRecoverTheirPassword() {
        UserAccount user = new UserAccount("person@example.test", "hash");
        assertTrue(user.canRecoverPassword());
        user.disable();
        assertFalse(user.canRecoverPassword());
    }
}

package org.andre.construx.modules.attendance.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CheckInTest {
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final Instant MORNING = Instant.parse("2026-03-10T15:00:00Z");
    private static final Instant NOON = Instant.parse("2026-03-10T18:00:00Z");

    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
    private final OrganizationMembership owner = organization.getMemberships().iterator().next();
    private final Project site = new Project(organization, "Tower");

    @Test
    void aNewCheckInIsActive() {
        CheckIn checkIn = new CheckIn(site, owner, DAY, MORNING, owner);

        assertFalse(checkIn.isRemoved());
        assertNull(checkIn.getRemovedAt());
        assertNull(checkIn.getRemovedBy());
    }

    @Test
    void removingOnItsOwnDayKeepsWhoAndWhen() {
        CheckIn checkIn = new CheckIn(site, owner, DAY, MORNING, owner);

        checkIn.remove(owner, NOON, DAY);

        assertTrue(checkIn.isRemoved());
        assertEquals(NOON, checkIn.getRemovedAt());
        assertSame(owner, checkIn.getRemovedBy());
        assertEquals(MORNING, checkIn.getCheckedInAt());
    }

    @Test
    void aCheckInOfAnotherDayIsLocked() {
        CheckIn checkIn = new CheckIn(site, owner, DAY, MORNING, owner);

        assertThrows(CheckInLockedException.class,
                () -> checkIn.remove(owner, Instant.parse("2026-03-11T15:00:00Z"), DAY.plusDays(1)));
        assertFalse(checkIn.isRemoved());
    }

    @Test
    void removingTwiceKeepsTheFirstRemoval() {
        CheckIn checkIn = new CheckIn(site, owner, DAY, MORNING, owner);
        OrganizationMembership other = new Organization("Other", new UserAccount("x@acme.com", "hash"))
                .getMemberships().iterator().next();
        checkIn.remove(owner, NOON, DAY);

        checkIn.remove(other, NOON.plusSeconds(60), DAY);
        checkIn.remove(other, NOON.plusSeconds(60), DAY.plusDays(1));

        assertEquals(NOON, checkIn.getRemovedAt());
        assertSame(owner, checkIn.getRemovedBy());
    }

    @Test
    void aRemovalNeedsWhoAndWhen() {
        CheckIn checkIn = new CheckIn(site, owner, DAY, MORNING, owner);

        assertThrows(IllegalArgumentException.class, () -> checkIn.remove(null, NOON, DAY));
        assertThrows(IllegalArgumentException.class, () -> checkIn.remove(owner, null, DAY));
        assertFalse(checkIn.isRemoved());
    }
}

package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    private final Instant inTenDays = Instant.now().plus(10, ChronoUnit.DAYS);
    private Goal goal;
    private ProjectAccess access;

    @BeforeEach
    void setUp() {
        Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
        Project project = new Project(organization, "Tower");
        access = new ProjectAccess(project, organization.getMemberships().iterator().next());
        goal = new Goal(project, "Foundations", inTenDays);
    }

    @Test
    void renameTrimsAndValidates() {
        goal.rename("  Structure  ");
        assertEquals("Structure", goal.getName());
        assertThrows(IllegalArgumentException.class, () -> goal.rename(" "));
        assertThrows(IllegalArgumentException.class, () -> goal.rename("x".repeat(161)));
    }

    @Test
    void dueDateCannotMoveBeforeItsLatestActivity() {
        new Activity(goal, "Pour", access, inTenDays.minus(2, ChronoUnit.DAYS));
        assertThrows(IllegalArgumentException.class, () -> goal.changeDueAt(inTenDays.minus(3, ChronoUnit.DAYS)));
        goal.changeDueAt(inTenDays.minus(2, ChronoUnit.DAYS));
        assertEquals(inTenDays.minus(2, ChronoUnit.DAYS), goal.getDueAt());
        assertThrows(IllegalArgumentException.class, () -> goal.changeDueAt(null));
    }

    @Test
    void completedOnlyWhenItHasActivitiesAndAllAreDone() {
        assertFalse(goal.isCompleted());
        Activity activity = new Activity(goal, "Pour", access, inTenDays);
        assertFalse(goal.isCompleted());
        activity.complete(Instant.now());
        assertTrue(goal.isCompleted());
    }
}

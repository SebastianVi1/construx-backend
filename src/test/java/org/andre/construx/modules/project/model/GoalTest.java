package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    private final Instant now = Instant.now();
    private final Instant inTenDays = now.plus(10, ChronoUnit.DAYS);
    private Project project;
    private Goal goal;
    private ProjectAccess access;

    @BeforeEach
    void setUp() {
        Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
        project = new Project(organization, "Tower");
        access = new ProjectAccess(project, organization.getMemberships().iterator().next());
        goal = new Goal(project, "Foundations", now, inTenDays);
    }

    private Instant day(int days) {
        return now.plus(days, ChronoUnit.DAYS);
    }

    @Test
    void renameTrimsAndValidates() {
        goal.rename("  Structure  ");
        assertEquals("Structure", goal.getName());
        assertThrows(IllegalArgumentException.class, () -> goal.rename(" "));
        assertThrows(IllegalArgumentException.class, () -> goal.rename("x".repeat(161)));
    }

    @Test
    void startIsRequiredAndCannotBeAfterDue() {
        assertEquals(now, goal.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> new Goal(project, "Late", inTenDays.plusSeconds(1), inTenDays));
        assertThrows(IllegalArgumentException.class, () -> new Goal(project, "Late", null, inTenDays));
        new Goal(project, "One day", inTenDays, inTenDays);
    }

    @Test
    void dueDateCannotMoveBeforeItsLatestActivity() {
        new Activity(goal, "Pour", access, now, inTenDays.minus(2, ChronoUnit.DAYS));
        assertThrows(IllegalArgumentException.class, () -> goal.changeDueAt(inTenDays.minus(3, ChronoUnit.DAYS)));
        goal.changeDueAt(inTenDays.minus(2, ChronoUnit.DAYS));
        assertEquals(inTenDays.minus(2, ChronoUnit.DAYS), goal.getDueAt());
        assertThrows(IllegalArgumentException.class, () -> goal.changeDueAt(null));
    }

    @Test
    void dueDateCannotMoveBeforeStart() {
        assertThrows(IllegalArgumentException.class, () -> goal.changeDueAt(now.minusSeconds(1)));
    }

    @Test
    void startDateCannotMovePastItsEarliestActivity() {
        new Activity(goal, "Pour", access, day(3), day(5));
        assertThrows(IllegalArgumentException.class, () -> goal.changeStartAt(day(4)));
        goal.changeStartAt(day(3));
        assertEquals(day(3), goal.getStartAt());
        goal.changeStartAt(day(-1));
        assertEquals(day(-1), goal.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> goal.changeStartAt(null));
    }

    @Test
    void startDateCannotMovePastDue() {
        assertThrows(IllegalArgumentException.class, () -> goal.changeStartAt(inTenDays.plusSeconds(1)));
    }

    @Test
    void changeDatesAcceptsAMoveThatIsOnlyValidAsAPair() {
        goal.changeDates(day(20), day(25));
        assertEquals(day(20), goal.getStartAt());
        assertEquals(day(25), goal.getDueAt());
        goal.changeDates(day(1), day(2));
        assertEquals(day(1), goal.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> goal.changeDates(day(3), day(2)));
    }

    @Test
    void changeDatesStillWrapsItsActivities() {
        new Activity(goal, "Pour", access, day(3), day(5));
        assertThrows(IllegalArgumentException.class, () -> goal.changeDates(day(4), day(8)));
        assertThrows(IllegalArgumentException.class, () -> goal.changeDates(day(0), day(4)));
        assertEquals(now, goal.getStartAt());
        assertEquals(inTenDays, goal.getDueAt());
    }

    @Test
    void shiftMovesTheGoalAndEveryActivityKeepingDurations() {
        Activity pour = new Activity(goal, "Pour", access, day(1), day(4));
        Activity cure = new Activity(goal, "Cure", access, day(4), day(10));
        goal.shift(Duration.ofDays(5));
        assertEquals(day(5), goal.getStartAt());
        assertEquals(day(15), goal.getDueAt());
        assertEquals(day(6), pour.getStartAt());
        assertEquals(day(9), pour.getDueAt());
        assertEquals(day(9), cure.getStartAt());
        assertEquals(day(15), cure.getDueAt());
        goal.shift(Duration.ofDays(-7));
        assertEquals(day(-2), goal.getStartAt());
        assertEquals(day(-1), pour.getStartAt());
    }

    @Test
    void shiftByZeroOrNothingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> goal.shift(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> goal.shift(null));
        assertEquals(now, goal.getStartAt());
    }

    @Test
    void completedOnlyWhenItHasActivitiesAndAllAreDone() {
        assertFalse(goal.isCompleted());
        Activity activity = new Activity(goal, "Pour", access, now, inTenDays);
        assertFalse(goal.isCompleted());
        activity.complete(Instant.now());
        assertTrue(goal.isCompleted());
    }

    @Test
    void colorIsAHexValueAndCanBeCleared() {
        assertNull(goal.getColor());
        goal.changeColor("#1E90FF");
        assertEquals("#1e90ff", goal.getColor());
        goal.changeColor(null);
        assertNull(goal.getColor());
        assertThrows(IllegalArgumentException.class, () -> goal.changeColor("blue"));
        assertThrows(IllegalArgumentException.class, () -> goal.changeColor("#12345"));
        assertThrows(IllegalArgumentException.class, () -> goal.changeColor("#gggggg"));
    }
}

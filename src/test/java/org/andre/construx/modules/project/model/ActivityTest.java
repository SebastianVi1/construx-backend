package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class ActivityTest {
    private final Instant goalStart = Instant.now();
    private final Instant goalDue = goalStart.plus(10, ChronoUnit.DAYS);
    private Activity activity;
    private Organization organization;
    private Project project;
    private Goal goal;
    private ProjectAccess access;

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
        project = new Project(organization, "Tower");
        access = new ProjectAccess(project, organization.getMemberships().iterator().next());
        goal = new Goal(project, "Phase", goalStart, goalDue);
        activity = new Activity(goal, "Pour", access, day(1), goalDue.minus(1, ChronoUnit.DAYS));
    }

    private Instant day(int days) {
        return goalStart.plus(days, ChronoUnit.DAYS);
    }

    @Test
    void renameTrimsAndValidates() {
        activity.rename("  Pour slab  ");
        assertEquals("Pour slab", activity.getName());
        assertThrows(IllegalArgumentException.class, () -> activity.rename(""));
    }

    @Test
    void startIsRequiredAndCannotBeAfterDue() {
        assertEquals(day(1), activity.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> new Activity(goal, "Late", access, day(3), day(2)));
        assertThrows(IllegalArgumentException.class, () -> new Activity(goal, "Late", access, null, day(2)));
    }

    @Test
    void startCannotBeBeforeTheGoalStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new Activity(goal, "Early", access, goalStart.minusSeconds(1), day(2)));
        new Activity(goal, "On time", access, goalStart, day(2));
    }

    @Test
    void rescheduleStaysWithinTheGoal() {
        activity.reschedule(goalDue);
        assertEquals(goalDue, activity.getDueAt());
        assertThrows(IllegalArgumentException.class, () -> activity.reschedule(goalDue.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> activity.reschedule(null));
    }

    @Test
    void rescheduleCannotEndBeforeTheStart() {
        assertThrows(IllegalArgumentException.class, () -> activity.reschedule(day(1).minusSeconds(1)));
        activity.reschedule(day(1));
        assertEquals(day(1), activity.getDueAt());
    }

    @Test
    void changeStartAtStaysWithinTheGoalAndBeforeTheDue() {
        activity.changeStartAt(goalStart);
        assertEquals(goalStart, activity.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> activity.changeStartAt(goalStart.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> activity.changeStartAt(day(9).plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> activity.changeStartAt(null));
    }

    @Test
    void changeDatesAcceptsAMoveThatIsOnlyValidAsAPair() {
        activity.changeDates(day(1), day(2));
        activity.changeDates(day(8), day(9));
        assertEquals(day(8), activity.getStartAt());
        assertEquals(day(9), activity.getDueAt());
        activity.changeDates(day(1), day(2));
        assertEquals(day(1), activity.getStartAt());
        assertThrows(IllegalArgumentException.class, () -> activity.changeDates(day(3), day(2)));
        assertThrows(IllegalArgumentException.class, () -> activity.changeDates(day(-1), day(2)));
        assertThrows(IllegalArgumentException.class, () -> activity.changeDates(day(1), day(11)));
        assertEquals(day(1), activity.getStartAt());
        assertEquals(day(2), activity.getDueAt());
    }

    @Test
    void lifecycleTodoInProgressDoneAndNoReopen() {
        assertEquals(ActivityStatus.TODO, activity.getStatus());
        activity.start();
        assertThrows(IllegalStateException.class, () -> activity.start());
        Instant now = Instant.now();
        activity.complete(now);
        assertEquals(ActivityStatus.DONE, activity.getStatus());
        assertEquals(now, activity.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> activity.complete(now));
    }

    @Test
    void assigneeMustHaveAccessToTheSameSite() {
        Project other = new Project(organization, "Other");
        ProjectAccess foreign = new ProjectAccess(other, organization.getMemberships().iterator().next());
        assertThrows(IllegalArgumentException.class, () -> activity.assignTo(foreign));
    }

    @Test
    void colorIsAHexValueAndCanBeCleared() {
        assertNull(activity.getColor());
        activity.changeColor("#FF8800");
        assertEquals("#ff8800", activity.getColor());
        activity.changeColor(null);
        assertNull(activity.getColor());
        assertThrows(IllegalArgumentException.class, () -> activity.changeColor("#ff88001"));
    }
}

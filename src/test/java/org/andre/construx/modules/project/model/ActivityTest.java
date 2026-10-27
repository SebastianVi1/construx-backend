package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class ActivityTest {
    private final Instant goalDue = Instant.now().plus(10, ChronoUnit.DAYS);
    private Activity activity;
    private Organization organization;
    private Project project;

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
        project = new Project(organization, "Tower");
        ProjectAccess access = new ProjectAccess(project, organization.getMemberships().iterator().next());
        activity = new Activity(new Goal(project, "Phase", goalDue), "Pour", access, goalDue.minus(1, ChronoUnit.DAYS));
    }

    @Test
    void renameTrimsAndValidates() {
        activity.rename("  Pour slab  ");
        assertEquals("Pour slab", activity.getName());
        assertThrows(IllegalArgumentException.class, () -> activity.rename(""));
    }

    @Test
    void rescheduleStaysWithinTheGoal() {
        activity.reschedule(goalDue);
        assertEquals(goalDue, activity.getDueAt());
        assertThrows(IllegalArgumentException.class, () -> activity.reschedule(goalDue.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> activity.reschedule(null));
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
}

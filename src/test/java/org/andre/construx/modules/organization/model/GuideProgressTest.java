package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GuideProgressTest {
    private final GuideProgress progress = new GuideProgress(mock(OrganizationMembership.class));

    @Test
    void markStoresStatusAndUpdatesTimestamp() {
        progress.mark("create-site", GuideStepStatus.COMPLETED);

        assertEquals(Map.of("create-site", GuideStepStatus.COMPLETED), progress.getSteps());
        assertNotNull(progress.getUpdatedAt());
    }

    @Test
    void markReplacesAnEarlierStatus() {
        progress.mark("create-site", GuideStepStatus.SKIPPED);
        progress.mark("create-site", GuideStepStatus.COMPLETED);

        assertEquals(Map.of("create-site", GuideStepStatus.COMPLETED), progress.getSteps());
    }

    @Test
    void markRejectsInvalidIds() {
        for (String id : new String[] {"Create", "", "a".repeat(65), "x/y", "1-step", "-a", null}) {
            RuleViolationException error = assertThrows(RuleViolationException.class,
                    () -> progress.mark(id, GuideStepStatus.COMPLETED), String.valueOf(id));
            assertEquals("GUIDE_STEP_INVALID", error.getCode());
        }
        assertTrue(progress.getSteps().isEmpty());
    }

    @Test
    void markAcceptsTheLongestValidId() {
        progress.mark("a".repeat(64), GuideStepStatus.COMPLETED);

        assertEquals(1, progress.getSteps().size());
    }

    @Test
    void markRejectsTheFiftyFirstStep() {
        for (int i = 0; i < 50; i++) {
            progress.mark("step-" + i, GuideStepStatus.COMPLETED);
        }

        RuleViolationException error = assertThrows(RuleViolationException.class,
                () -> progress.mark("step-50", GuideStepStatus.COMPLETED));
        assertEquals("GUIDE_TOO_MANY_STEPS", error.getCode());
        progress.mark("step-7", GuideStepStatus.SKIPPED);
        assertEquals(50, progress.getSteps().size());
        assertEquals(GuideStepStatus.SKIPPED, progress.getSteps().get("step-7"));
    }

    @Test
    void markRequiresAStatus() {
        assertThrows(IllegalArgumentException.class, () -> progress.mark("create-site", null));
    }

    @Test
    void dismissKeepsTheFirstTimestamp() {
        progress.dismiss(true);
        Instant first = progress.getDismissedAt();
        assertNotNull(first);

        progress.dismiss(true);
        assertEquals(first, progress.getDismissedAt());

        progress.dismiss(false);
        assertNull(progress.getDismissedAt());
    }
}

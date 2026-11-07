package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.project.model.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies the FloorPlan status state machine: PENDING → PROCESSING → READY / ERROR. */
class FloorPlanStatusMachineTest {

    private Project project;
    private OrganizationMembership member;

    @BeforeEach
    void setUp() {
        Organization org = mock(Organization.class);
        when(org.getId()).thenReturn(java.util.UUID.randomUUID());

        project = mock(Project.class);
        when(project.getId()).thenReturn(java.util.UUID.randomUUID());
        when(project.getOrganization()).thenReturn(org);

        member = mock(OrganizationMembership.class);
    }

    private FloorPlan newPlan() {
        return new FloorPlan(project, member, "Level 1", null, "Ground", 1, "application/pdf", 1024L);
    }

    @Test
    void newPlanStartsPending() {
        FloorPlan plan = newPlan();
        assertEquals(FloorPlanStatus.PENDING, plan.getStatus());
        assertNotNull(plan.getId());
        assertNotNull(plan.getOriginalKey());
        assertTrue(plan.getOriginalKey().endsWith(".pdf"));
    }

    @Test
    void happyPath_pendingToProcessingToReady() {
        FloorPlan plan = newPlan();
        plan.startProcessing();
        assertEquals(FloorPlanStatus.PROCESSING, plan.getStatus());

        Instant now = Instant.now();
        plan.markReady("orgs/x/rendered.png", now);
        assertEquals(FloorPlanStatus.READY, plan.getStatus());
        assertEquals("orgs/x/rendered.png", plan.getRenderedKey());
        assertEquals(now, plan.getCompletedAt());
        assertTrue(plan.isReady());
    }

    @Test
    void errorPath_processingToError() {
        FloorPlan plan = newPlan();
        plan.startProcessing();
        plan.markError("Page 2 is out of range; this PDF has 1 page(s)");
        assertEquals(FloorPlanStatus.ERROR, plan.getStatus());
        assertEquals("Page 2 is out of range; this PDF has 1 page(s)", plan.getErrorReason());
        assertFalse(plan.isReady());
    }

    @Test
    void startProcessingRequiresPending() {
        FloorPlan plan = newPlan();
        plan.startProcessing();
        assertThrows(IllegalStateException.class, plan::startProcessing);
    }

    @Test
    void markReadyRequiresProcessing() {
        FloorPlan plan = newPlan();
        assertThrows(IllegalStateException.class,
                () -> plan.markReady("some-key", Instant.now()));
    }

    @Test
    void imageContentTypeParsedCorrectly() {
        FloorPlan plan = new FloorPlan(project, member, "Site photo", null, null, 1, "image/jpeg", 2048L);
        assertTrue(plan.getOriginalKey().endsWith(".jpg"));
        assertFalse(plan.isPdf());
    }

    @Test
    void invalidContentTypeThrows() {
        assertThrows(org.andre.construx.shared.exception.RuleViolationException.class,
                () -> new FloorPlan(project, member, "Bad plan", null, null, 1, "application/dwg", 1024L));
    }

    @Test
    void oversizedFileThrows() {
        long tooBig = 51L * 1024 * 1024;
        assertThrows(org.andre.construx.shared.exception.RuleViolationException.class,
                () -> new FloorPlan(project, member, "Big plan", null, null, 1, "image/png", tooBig));
    }

    @Test
    void pageNumberZeroThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new FloorPlan(project, member, "Bad page", null, null, 0, "application/pdf", 1024L));
    }
}

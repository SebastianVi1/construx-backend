package org.andre.construx.modules.plan.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage.StoredObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectPlanTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
    private final Project project = new Project(organization, "Tower");
    private final OrganizationMembership owner = organization.getMemberships().iterator().next();

    private ProjectPlan plan(String fileName, String contentType, long size, String name) {
        return new ProjectPlan(project, owner, fileName, contentType, size, name, null);
    }

    @Test
    void aNewPlanIsPendingAndItsKeyLivesUnderItsSite() {
        ProjectPlan plan = plan("Ground floor.PNG", "image/png", 1_000, null);
        assertEquals(PlanStatus.PENDING, plan.getStatus());
        assertEquals("Ground floor", plan.getName());
        assertEquals("Ground floor.PNG", plan.getOriginalFileName());
        assertNotNull(plan.getId());
        assertTrue(plan.getStorageKey().matches("orgs/[^/]+/projects/[^/]+/plans/" + plan.getId() + "/[0-9a-f-]{36}\\.png"),
                plan.getStorageKey());
        assertSame(owner, plan.getUploadedBy());
    }

    @Test
    void anExplicitNameIsTrimmedAndBounded() {
        assertEquals("Structure", plan("a.jpg", "image/jpeg", 10, "  Structure ").getName());
        assertThrows(IllegalArgumentException.class, () -> plan("a.jpg", "image/jpeg", 10, "x".repeat(121)));
        assertEquals(120, plan("y".repeat(200) + ".webp", "image/webp", 10, " ").getName().length());
    }

    @Test
    void onlySmallEnoughImagesAreAccepted() {
        RuleViolationException type = assertThrows(RuleViolationException.class,
                () -> plan("plan.pdf", "application/pdf", 10, null));
        assertEquals("PLAN_FILE_TYPE", type.getCode());
        RuleViolationException large = assertThrows(RuleViolationException.class,
                () -> plan("plan.png", "image/png", ProjectPlan.MAX_SIZE_BYTES + 1, null));
        assertEquals("PLAN_FILE_TOO_LARGE", large.getCode());
        assertThrows(RuleViolationException.class, () -> plan("plan.png", "image/png", 0, null));
        assertThrows(IllegalArgumentException.class, () -> plan(" ", "image/png", 10, null));
        assertDoesNotThrow(() -> plan("plan.png", "IMAGE/PNG", ProjectPlan.MAX_SIZE_BYTES, null));
    }

    @Test
    void aPlanBecomesReadyOnlyWhenTheStoredFileMatchesWhatWasAnnounced() {
        ProjectPlan plan = plan("plan.webp", "image/webp", 500, null);
        RuleViolationException mismatch = assertThrows(RuleViolationException.class,
                () -> plan.markReady(new StoredObject(499, "image/webp")));
        assertEquals("PLAN_UPLOAD_MISMATCH", mismatch.getCode());
        assertThrows(RuleViolationException.class, () -> plan.markReady(new StoredObject(500, "image/png")));
        assertEquals(PlanStatus.PENDING, plan.getStatus());

        plan.markReady(new StoredObject(500, "image/webp"));
        assertEquals(PlanStatus.READY, plan.getStatus());
        assertNotNull(plan.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> plan.markReady(new StoredObject(500, "image/webp")));
    }
}

package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ProjectTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));

    @Test
    void newProjectIsPlannedWithOptionalDetailsEmpty() {
        Project project = new Project(organization, "  Tower  ");
        assertEquals("Tower", project.getName());
        assertEquals(ProjectStatus.PLANNED, project.getStatus());
        assertNull(project.getAddress());
        assertNull(project.getStartDate());
        assertNull(project.getExpectedEndDate());
    }

    @Test
    void detailsAreTrimmedAndBlankAddressBecomesNull() {
        Project project = new Project(organization, "Tower");
        project.describe("  Av. Siempre Viva 742  ", LocalDate.of(2026, 11, 1), LocalDate.of(2027, 5, 30));
        assertEquals("Av. Siempre Viva 742", project.getAddress());
        assertEquals(LocalDate.of(2027, 5, 30), project.getExpectedEndDate());
        project.describe("   ", null, null);
        assertNull(project.getAddress());
    }

    @Test
    void endBeforeStartAndOverlongAddressAreRejected() {
        Project project = new Project(organization, "Tower");
        assertThrows(IllegalArgumentException.class,
                () -> project.describe(null, LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> project.describe("x".repeat(256), null, null));
        assertDoesNotThrow(() -> project.describe(null, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1)));
    }

    @Test
    void statusChangesAndIsRequired() {
        Project project = new Project(organization, "Tower");
        project.changeStatus(ProjectStatus.ARCHIVED);
        assertEquals(ProjectStatus.ARCHIVED, project.getStatus());
        assertThrows(IllegalArgumentException.class, () -> project.changeStatus(null));
    }

    @Test
    void budgetIsOptionalAndCanBeAssignedAndCleared() {
        Project project = new Project(organization, "Tower");
        assertNull(project.getAllocatedBudget());
        project.assignBudget(new BigDecimal("250000.5"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("250000.50"), project.getAllocatedBudget());
        project.assignBudget(BigDecimal.ZERO, BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"), project.getAllocatedBudget());
        project.assignBudget(null, BigDecimal.ZERO);
        assertNull(project.getAllocatedBudget());
    }

    @Test
    void negativeOverPreciseOrOversizedBudgetsAreRejected() {
        Project project = new Project(organization, "Tower");
        assertThrows(IllegalArgumentException.class, () -> project.assignBudget(new BigDecimal("-0.01"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> project.assignBudget(new BigDecimal("10.001"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> project.assignBudget(new BigDecimal("1000000000000"), BigDecimal.ZERO));
        assertDoesNotThrow(() -> project.assignBudget(new BigDecimal("999999999999.99"), BigDecimal.ZERO));
    }
}

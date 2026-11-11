package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.PinKind;
import org.andre.construx.modules.floorplan.model.PinStatus;
import org.andre.construx.modules.floorplan.model.PlanPin;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlanPinTest {

    private final FloorPlan plan = mock(FloorPlan.class);
    private final OrganizationMembership reporter = mock(OrganizationMembership.class);

    private PlanPin issue() {
        return PlanPin.issue(plan, reporter, 10, 20, "Cracked tile", null);
    }

    private PlanPin photo() {
        return PlanPin.photo(plan, reporter, 10, 20, null, null);
    }

    private static void assertRule(String code, Executable action) {
        assertEquals(code, assertThrows(RuleViolationException.class, action).getCode());
    }

    @Test
    void issueStartsOpen() {
        PlanPin pin = issue();
        assertEquals(PinKind.ISSUE, pin.getKind());
        assertEquals(PinStatus.OPEN, pin.getStatus());
        assertEquals("Cracked tile", pin.getTitle());
    }

    @Test
    void photoPinHasNoStatus() {
        PlanPin pin = photo();
        assertEquals(PinKind.PHOTO, pin.getKind());
        assertNull(pin.getStatus());
    }

    @Test
    void issueNeedsATitle() {
        assertRule("PIN_TITLE_REQUIRED", () -> PlanPin.issue(plan, reporter, 1, 1, null, null));
        assertRule("PIN_TITLE_REQUIRED", () -> PlanPin.issue(plan, reporter, 1, 1, "   ", null));
        assertRule("PIN_TITLE_REQUIRED", () -> issue().edit(" ", null, 1, 1));
    }

    @Test
    void rejectsNegativeOrNonFiniteCoordinates() {
        assertRule("PIN_OUT_OF_BOUNDS", () -> PlanPin.issue(plan, reporter, -1, 1, "t", null));
        assertRule("PIN_OUT_OF_BOUNDS", () -> PlanPin.issue(plan, reporter, 1, Double.NaN, "t", null));
        assertRule("PIN_OUT_OF_BOUNDS", () -> PlanPin.photo(plan, reporter, Double.POSITIVE_INFINITY, 1, null, null));
        assertRule("PIN_OUT_OF_BOUNDS", () -> issue().edit("t", null, 1, -0.5));
    }

    @Test
    void resolveThenCloseSetsTimestamps() {
        PlanPin pin = issue();
        pin.resolve();
        assertEquals(PinStatus.RESOLVED, pin.getStatus());
        assertNotNull(pin.getResolvedAt());
        pin.close();
        assertEquals(PinStatus.CLOSED, pin.getStatus());
        assertNotNull(pin.getClosedAt());
    }

    @Test
    void closeDirectlyFromOpenIsAllowed() {
        PlanPin pin = issue();
        pin.close();
        assertEquals(PinStatus.CLOSED, pin.getStatus());
    }

    @Test
    void reopenFromResolvedOrClosedClearsTimestamps() {
        PlanPin resolved = issue();
        resolved.resolve();
        resolved.reopen();
        assertEquals(PinStatus.OPEN, resolved.getStatus());
        assertNull(resolved.getResolvedAt());

        PlanPin closed = issue();
        closed.resolve();
        closed.close();
        closed.reopen();
        assertEquals(PinStatus.OPEN, closed.getStatus());
        assertNull(closed.getResolvedAt());
        assertNull(closed.getClosedAt());
    }

    @Test
    void resolveWhenNotOpenIsRejected() {
        PlanPin pin = issue();
        pin.resolve();
        assertRule("PIN_INVALID_TRANSITION", pin::resolve);
    }

    @Test
    void reopenWhenOpenIsRejected() {
        assertRule("PIN_INVALID_TRANSITION", () -> issue().reopen());
    }

    @Test
    void closeWhenClosedIsRejected() {
        PlanPin pin = issue();
        pin.close();
        assertRule("PIN_INVALID_TRANSITION", pin::close);
    }

    @Test
    void photoPinCannotBeAssignedOrTransitioned() {
        assertRule("PIN_NOT_AN_ISSUE", () -> photo().assign(reporter));
        assertRule("PIN_NOT_AN_ISSUE", () -> photo().resolve());
        assertRule("PIN_NOT_AN_ISSUE", () -> photo().close());
        assertRule("PIN_NOT_AN_ISSUE", () -> photo().reopen());
    }

    @Test
    void reporterMayChangeOnlyPhotoPinsAndOpenIssues() {
        assertTrue(photo().isOpenForReporter());
        PlanPin pin = issue();
        assertTrue(pin.isOpenForReporter());
        pin.resolve();
        assertFalse(pin.isOpenForReporter());
    }
}

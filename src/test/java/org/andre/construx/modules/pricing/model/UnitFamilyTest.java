package org.andre.construx.modules.pricing.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Archived Units and Families cannot be chosen for new Resources (docs/pricing.md §2.2). */
class UnitFamilyTest {
    private final UUID organizationId = UUID.randomUUID();

    @Test
    void anArchivedUnitIsNotUsable() {
        Unit unit = new Unit(organizationId, "%mo1", null);
        assertEquals("%MO1", unit.getCode());
        assertDoesNotThrow(unit::requireUsable);
        unit.archive(Instant.parse("2026-11-16T10:00:00Z"));
        unit.archive(Instant.parse("2026-11-17T10:00:00Z"));
        assertEquals(Instant.parse("2026-11-16T10:00:00Z"), unit.getArchivedAt());
        PricingConflictException e = assertThrows(PricingConflictException.class, unit::requireUsable);
        assertEquals("ARCHIVED_UNIT", e.getCode());
    }

    @Test
    void anArchivedFamilyIsNotUsable() {
        Family family = new Family(organizationId, "1:4", "Mezclas");
        assertDoesNotThrow(family::requireUsable);
        family.archive(Instant.parse("2026-11-16T10:00:00Z"));
        PricingConflictException e = assertThrows(PricingConflictException.class, family::requireUsable);
        assertEquals("ARCHIVED_FAMILY", e.getCode());
    }
}

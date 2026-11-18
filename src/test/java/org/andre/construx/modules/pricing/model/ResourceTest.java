package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ResourceTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
    private final Unit m3 = new Unit(organization, " m3 ", "Metro cúbico", CatalogEntrySource.MANUAL);
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");

    private Resource arena() {
        return new Resource(organization, "301-are-0101", ResourceType.MATERIAL, "Arena  de mina", m3, null, null,
                ResourceSource.MANUAL, now);
    }

    @Test
    void codesAreTrimmedAndUpperCasedButOtherwiseKept() {
        assertEquals("M3", m3.getCode());
        assertEquals("301-ARE-0101", arena().getCode());
        assertEquals("%MO1", new Unit(organization, "%mo1", null, CatalogEntrySource.MANUAL).getCode());
        assertEquals("1:4", new Family(organization, " 1:4", "Mortero", CatalogEntrySource.MANUAL).getCode());
        assertEquals("Arena de mina", arena().getName());
        PricingRuleException blank = assertThrows(PricingRuleException.class,
                () -> new Unit(organization, "  ", null, CatalogEntrySource.MANUAL));
        assertEquals("REQUIRED", blank.getCode());
        assertThrows(PricingRuleException.class,
                () -> new Unit(organization, "X".repeat(21), null, CatalogEntrySource.MANUAL));
    }

    @Test
    void aNewResourceHasNoCostYet() {
        Resource arena = arena();
        assertNull(arena.getCurrentCost());
        assertNull(arena.getCostUpdatedAt());
        assertFalse(arena.isComposite());
        assertFalse(arena.isArchived());
        assertEquals(now, arena.getCreatedAt());
    }

    @Test
    void recordingACostMakesItTheCurrentCost() {
        Resource arena = arena();
        ResourceCostHistory entry = arena.recordCost(new BigDecimal("290.5"), CostSource.MANUAL, null, null,
                " SKU-1 ", "  precio  de lista ", now);
        assertEquals(new BigDecimal("290.50"), entry.getAmount());
        assertEquals(now, entry.getEffectiveFrom());
        assertEquals("SKU-1", entry.getSku());
        assertEquals("precio de lista", entry.getNote());
        assertSame(organization, entry.getOrganization());
        assertEquals(new BigDecimal("290.50"), arena.getCurrentCost());
        assertEquals(now, arena.getCostUpdatedAt());
        assertDoesNotThrow(() -> arena.recordCost(BigDecimal.ZERO, CostSource.CSV, null, null, null, null, now));
        assertEquals(new BigDecimal("0.00"), arena.getCurrentCost());
    }

    @Test
    void costsAreNonNegativeWithAtMostTwoDecimals() {
        Resource arena = arena();
        assertEquals("INVALID", assertThrows(PricingRuleException.class, () -> arena.recordCost(
                new BigDecimal("-0.01"), CostSource.MANUAL, null, null, null, null, now)).getCode());
        assertEquals("TOO_MANY_DECIMALS", assertThrows(PricingRuleException.class, () -> arena.recordCost(
                new BigDecimal("1.005"), CostSource.MANUAL, null, null, null, null, now)).getCode());
        assertEquals("INVALID", assertThrows(PricingRuleException.class, () -> arena.recordCost(
                new BigDecimal("1000000000000"), CostSource.MANUAL, null, null, null, null, now)).getCode());
        assertEquals("REQUIRED", assertThrows(PricingRuleException.class, () -> arena.recordCost(
                null, CostSource.MANUAL, null, null, null, null, now)).getCode());
        assertNull(arena.getCurrentCost());
    }

    @Test
    void catalogRowsMustBelongToTheResourcesOrganization() {
        Organization other = new Organization("Other", new UserAccount("x@other.com", "hash"));
        Unit foreignUnit = new Unit(other, "M3", null, CatalogEntrySource.MANUAL);
        Supplier foreignSupplier = new Supplier(other, "Depot", null, null);
        assertThrows(IllegalArgumentException.class, () -> new Resource(organization, "A", ResourceType.MATERIAL, "A",
                foreignUnit, null, null, ResourceSource.MANUAL, now));
        assertThrows(IllegalArgumentException.class, () -> new Resource(organization, "A", ResourceType.MATERIAL, "A",
                m3, new Family(other, "F", "F", CatalogEntrySource.MANUAL), null, ResourceSource.MANUAL, now));
        assertThrows(IllegalArgumentException.class, () -> arena().recordCost(BigDecimal.ONE, CostSource.MANUAL, null,
                foreignSupplier, null, null, now));
    }

    @Test
    void archivingKeepsTheFirstTime() {
        Resource arena = arena();
        Instant later = now.plusSeconds(60);
        arena.archive(now);
        arena.archive(later);
        assertEquals(now, arena.getArchivedAt());
    }
}

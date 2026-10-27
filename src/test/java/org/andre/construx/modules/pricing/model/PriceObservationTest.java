package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PriceObservationTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));
    private final Material material = new Material(organization, "Cemento", MaterialUnit.BAG,
            MaterialCategory.CEMENT_AGGREGATES, null);
    private final Supplier supplier = new Supplier(organization, "Home Depot", null, null);
    private final LocalDate today = LocalDate.of(2026, 10, 27);
    private final Instant now = Instant.parse("2026-10-27T12:00:00Z");

    private PriceObservation observe(String price, LocalDate on) {
        return new PriceObservation(material, supplier, price == null ? null : new BigDecimal(price), on, null,
                PriceSource.MANUAL, today, now);
    }

    @Test
    void priceIsKeptExactWithTwoDecimals() {
        PriceObservation observation = new PriceObservation(material, supplier, new BigDecimal("245.5"), today,
                "  SKU-1  ", PriceSource.MANUAL, today, now);
        assertEquals(new BigDecimal("245.50"), observation.getPrice());
        assertEquals("SKU-1", observation.getSku());
        assertEquals(PriceSource.MANUAL, observation.getSource());
        assertEquals(today, observation.getObservedOn());
        assertEquals(now, observation.getCreatedAt());
        assertSame(organization, observation.getOrganization());
        assertEquals(new BigDecimal("10.00"), observe("10.000", today).getPrice());
        assertNull(observe("1", today).getSku());
    }

    @Test
    void priceMustBePositiveWithAtMostTwoDecimalsAndFitTheColumn() {
        assertThrows(IllegalArgumentException.class, () -> observe("0", today));
        assertThrows(IllegalArgumentException.class, () -> observe("0.00", today));
        assertThrows(IllegalArgumentException.class, () -> observe("-1", today));
        assertThrows(IllegalArgumentException.class, () -> observe("1.234", today));
        assertThrows(IllegalArgumentException.class, () -> observe("10000000000", today));
        assertThrows(IllegalArgumentException.class, () -> observe(null, today));
        assertDoesNotThrow(() -> observe("9999999999.99", today));
        assertDoesNotThrow(() -> observe("0.01", today));
    }

    @Test
    void observedOnIsRequiredAndCannotBeInTheFuture() {
        assertThrows(IllegalArgumentException.class, () -> observe("1", today.plusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> observe("1", null));
        assertDoesNotThrow(() -> observe("1", today.minusYears(1)));
    }

    @Test
    void archivedMaterialOrSupplierCannotReceiveObservations() {
        Material archived = new Material(organization, "Old", MaterialUnit.BAG, MaterialCategory.STEEL, null);
        archived.archive(now);
        assertThrows(PricingConflictException.class, () -> new PriceObservation(archived, supplier,
                BigDecimal.ONE, today, null, PriceSource.MANUAL, today, now));
        Supplier closed = new Supplier(organization, "Closed", null, null);
        closed.archive(now);
        assertThrows(PricingConflictException.class, () -> new PriceObservation(material, closed,
                BigDecimal.ONE, today, null, PriceSource.MANUAL, today, now));
    }

    @Test
    void materialAndSupplierMustShareTheOrganization() {
        Organization other = new Organization("Other", new UserAccount("x@other.com", "hash"));
        Supplier foreign = new Supplier(other, "Foreign", null, null);
        assertThrows(IllegalArgumentException.class, () -> new PriceObservation(material, foreign,
                BigDecimal.ONE, today, null, PriceSource.MANUAL, today, now));
    }

    @Test
    void skuIsBoundedAndSourceRequired() {
        assertThrows(IllegalArgumentException.class, () -> new PriceObservation(material, supplier,
                BigDecimal.ONE, today, "s".repeat(65), PriceSource.MANUAL, today, now));
        assertThrows(IllegalArgumentException.class, () -> new PriceObservation(material, supplier,
                BigDecimal.ONE, today, null, null, today, now));
    }
}

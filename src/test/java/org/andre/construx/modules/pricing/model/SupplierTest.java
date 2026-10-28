package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SupplierTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));

    @Test
    void fieldsAreCleanedAndBlankOptionalsBecomeNull() {
        Supplier supplier = new Supplier(organization, "  Home   Depot ", "  Periférico  Sur ", " https://www.homedepot.com.mx ");
        assertEquals("Home Depot", supplier.getName());
        assertEquals("Periférico Sur", supplier.getBranch());
        assertEquals("https://www.homedepot.com.mx", supplier.getWebsite());
        Supplier bare = new Supplier(organization, "Ferretería Lupita", " ", "");
        assertNull(bare.getBranch());
        assertNull(bare.getWebsite());
        assertFalse(bare.isArchived());
    }

    @Test
    void keyIgnoresCaseAndWhitespaceOfNameAndBranch() {
        Supplier a = new Supplier(organization, "Home Depot", "Periférico Sur", null);
        assertEquals(a.getNormalizedKey(), Supplier.keyOf(" HOME  depot", "periférico   SUR "));
        assertNotEquals(a.getNormalizedKey(), Supplier.keyOf("Home Depot", "Satélite"));
        assertNotEquals(a.getNormalizedKey(), Supplier.keyOf("Home Depot", null));
        assertEquals(Supplier.keyOf("Home Depot", null), Supplier.keyOf("home depot", " "));
    }

    @Test
    void websiteMustBeAnHttpOrHttpsUrl() {
        assertDoesNotThrow(() -> new Supplier(organization, "A", null, "http://example.mx/tienda"));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "A", null, "ftp://example.mx"));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "A", null, "example.mx"));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "A", null, "https://"));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "A", null, "https://exa mple.mx"));
        assertThrows(IllegalArgumentException.class,
                () -> new Supplier(organization, "A", null, "https://example.mx/" + "x".repeat(250)));
    }

    @Test
    void nameIsRequiredAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "  ", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "x".repeat(161), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Supplier(organization, "A", "b".repeat(121), null));
    }

    @Test
    void describeAndArchive() {
        Supplier supplier = new Supplier(organization, "A", null, null);
        supplier.describe("B", "Centro", "https://b.mx");
        assertEquals("B", supplier.getName());
        assertEquals("Centro", supplier.getBranch());
        assertEquals(Supplier.keyOf("b", "centro"), supplier.getNormalizedKey());
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        supplier.archive(at);
        supplier.archive(Instant.parse("2026-10-05T00:00:00Z"));
        assertEquals(at, supplier.getArchivedAt());
    }
}

package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MaterialTest {
    private final Organization organization = new Organization("Acme", new UserAccount("o@acme.com", "hash"));

    @Test
    void namesAndBrandsAreTrimmedAndInnerWhitespaceCollapsed() {
        Material material = new Material(organization, "  Cemento   gris  ", MaterialUnit.BAG,
                MaterialCategory.CEMENT_AGGREGATES, "  Cemex   Tolteca ");
        assertEquals("Cemento gris", material.getName());
        assertEquals("Cemex Tolteca", material.getBrand());
        assertEquals(MaterialUnit.BAG, material.getUnit());
        assertEquals(MaterialCategory.CEMENT_AGGREGATES, material.getCategory());
        assertFalse(material.isArchived());
        assertNull(material.getArchivedAt());
    }

    @Test
    void blankBrandIsStoredAsNull() {
        Material material = new Material(organization, "Arena", MaterialUnit.CUBIC_METER,
                MaterialCategory.CEMENT_AGGREGATES, "   ");
        assertNull(material.getBrand());
    }

    @Test
    void keyIgnoresCaseAndExtraWhitespaceButNotUnitOrBrand() {
        Material a = new Material(organization, "Cemento gris", MaterialUnit.BAG, MaterialCategory.CEMENT_AGGREGATES, "Cemex");
        Material b = new Material(organization, "  CEMENTO   Gris ", MaterialUnit.BAG, MaterialCategory.STEEL, " cemex ");
        assertEquals(a.getNormalizedKey(), b.getNormalizedKey());
        assertEquals(a.getNormalizedKey(), Material.keyOf(" cemento  GRIS", MaterialUnit.BAG, "CEMEX"));
        assertNotEquals(a.getNormalizedKey(), Material.keyOf("Cemento gris", MaterialUnit.KILOGRAM, "Cemex"));
        assertNotEquals(a.getNormalizedKey(), Material.keyOf("Cemento gris", MaterialUnit.BAG, null));
        assertEquals(Material.keyOf("Arena", MaterialUnit.BAG, null), Material.keyOf("arena", MaterialUnit.BAG, "  "));
    }

    @Test
    void textRuleFailuresCarryTheFieldAndAStableCode() {
        PriceObservationTest.assertRule("name", "REQUIRED",
                () -> new Material(organization, " ", MaterialUnit.BAG, MaterialCategory.STEEL, null));
        PriceObservationTest.assertRule("name", "INVALID",
                () -> new Material(organization, "x".repeat(161), MaterialUnit.BAG, MaterialCategory.STEEL, null));
        PriceObservationTest.assertRule("brand", "INVALID",
                () -> new Material(organization, "V", MaterialUnit.BAG, MaterialCategory.STEEL, "b".repeat(81)));
        PriceObservationTest.assertRule("name", "REQUIRED", () -> new Supplier(organization, "", null, null));
        PriceObservationTest.assertRule("branch", "INVALID",
                () -> new Supplier(organization, "A", "b".repeat(121), null));
    }

    @Test
    void requiredFieldsAndLengthsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new Material(organization, " ", MaterialUnit.BAG, MaterialCategory.STEEL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Material(organization, "x".repeat(161), MaterialUnit.BAG, MaterialCategory.STEEL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Material(organization, "Varilla", null, MaterialCategory.STEEL, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Material(organization, "Varilla", MaterialUnit.PIECE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Material(organization, "Varilla", MaterialUnit.PIECE, MaterialCategory.STEEL, "b".repeat(81)));
        assertThrows(IllegalArgumentException.class,
                () -> new Material(null, "Varilla", MaterialUnit.PIECE, MaterialCategory.STEEL, null));
    }

    @Test
    void describeChangesEveryFieldAndTheKey() {
        Material material = new Material(organization, "Varilla", MaterialUnit.PIECE, MaterialCategory.STEEL, null);
        material.describe("Varilla 3/8", MaterialUnit.METER, MaterialCategory.STEEL, "Deacero");
        assertEquals("Varilla 3/8", material.getName());
        assertEquals(MaterialUnit.METER, material.getUnit());
        assertEquals("Deacero", material.getBrand());
        assertEquals(Material.keyOf("varilla 3/8", MaterialUnit.METER, "deacero"), material.getNormalizedKey());
    }

    @Test
    void archiveIsKeptOnceSet() {
        Material material = new Material(organization, "Varilla", MaterialUnit.PIECE, MaterialCategory.STEEL, null);
        Instant first = Instant.parse("2026-10-01T00:00:00Z");
        material.archive(first);
        material.archive(Instant.parse("2026-10-02T00:00:00Z"));
        assertTrue(material.isArchived());
        assertEquals(first, material.getArchivedAt());
    }
}

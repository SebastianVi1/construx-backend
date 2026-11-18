package org.andre.construx.modules.pricing.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The catalog core of V38 on a schema built only by Flyway, validated by Hibernate (the flow tests use
 * Hibernate's create-drop schema). The conversion of existing rows is {@link CatalogConversionTest}.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:pricing-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PricingMigrationTest {
    @Autowired JdbcTemplate jdbc;

    private UUID org;
    private UUID unit;
    private UUID family;
    private UUID supplier;

    @BeforeEach
    void setUp() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, user + "@example.test");
        org = UUID.randomUUID();
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', 'Acme')",
                org, user);
        unit = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, 'M3', null, 'MANUAL')",
                unit, org);
        family = UUID.randomUUID();
        jdbc.update("insert into family (id, organization_id, code, name, source) values (?, ?, '301', 'AGREGADOS', 'MANUAL')",
                family, org);
        supplier = UUID.randomUUID();
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'Depot', ?)",
                supplier, org, "depot|" + supplier);
    }

    private UUID resource(String code, String type) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, family_id, supplier_id, "
                        + "source, created_at, updated_at) values (?, ?, ?, ?, 'Arena', ?, ?, ?, 'NEODATA_CONSTRUBASE', ?, ?)",
                id, org, code, type, unit, family, supplier, now, now);
        return id;
    }

    private void cost(UUID resource, String amount, String source) {
        jdbc.update("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, "
                        + "source, supplier_id, sku, note) values (?, ?, ?, ?, ?, ?, ?, 'SKU-1', 'nota')",
                UUID.randomUUID(), org, resource, new java.math.BigDecimal(amount), Timestamp.from(Instant.now()),
                source, supplier);
    }

    @Test
    void aResourceWithItsCostHistoryCanBeStored() {
        UUID arena = resource("301-ARE-0101", "MATERIAL");
        assertDoesNotThrow(() -> cost(arena, "290.00", "NEODATA_CONSTRUBASE"));
        assertDoesNotThrow(() -> cost(arena, "0", "MANUAL"));
        assertDoesNotThrow(() -> cost(arena, "1.50", "FASAR"));
        assertEquals(3L, jdbc.queryForObject("select count(*) from resource_cost_history where resource_id = ?",
                Long.class, arena));
        assertEquals(false, jdbc.queryForObject("select is_composite from resource where id = ?", Boolean.class, arena));
    }

    @Test
    void codesAreUniquePerOrganization() {
        resource("301-ARE-0101", "MATERIAL");
        assertThrows(DataIntegrityViolationException.class, () -> resource("301-ARE-0101", "LABOR"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into unit (id, organization_id, code, source) values (?, ?, 'M3', 'MANUAL')", UUID.randomUUID(), org));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into family (id, organization_id, code, name, source) values (?, ?, '301', 'X', 'MANUAL')",
                UUID.randomUUID(), org));
        String catalog = "insert into catalog (id, organization_id, currency, created_at) values (?, ?, 'MXN', ?)";
        jdbc.update(catalog, UUID.randomUUID(), org, Timestamp.from(Instant.now()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(catalog, UUID.randomUUID(), org, Timestamp.from(Instant.now())));
    }

    @Test
    void supplierCodesAreUniqueWhenPresent() {
        jdbc.update("update supplier set code = 'P-1' where id = ?", supplier);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into supplier (id, organization_id, name, normalized_key, code) values (?, ?, 'Otro', 'otro|', 'P-1')",
                UUID.randomUUID(), org));
        assertDoesNotThrow(() -> jdbc.update(
                "insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'A', 'a|')", UUID.randomUUID(), org));
        assertDoesNotThrow(() -> jdbc.update(
                "insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'B', 'b|')", UUID.randomUUID(), org));
        assertEquals(List.of("MANUAL"), jdbc.queryForList("select distinct source from supplier where organization_id = ?",
                String.class, org));
        assertEquals(List.of(false), jdbc.queryForList("select distinct is_default from supplier where organization_id = ?",
                Boolean.class, org));
    }

    @Test
    void enumColumnsAndAmountsAreChecked() {
        assertThrows(DataIntegrityViolationException.class, () -> resource("X-1", "GANG"));
        UUID arena = resource("X-2", "MATERIAL");
        assertThrows(DataIntegrityViolationException.class, () -> cost(arena, "-0.01", "MANUAL"));
        assertThrows(DataIntegrityViolationException.class, () -> cost(arena, "1", "SCRAPER"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into unit (id, organization_id, code, source) values (?, ?, 'KG', 'CSV')", UUID.randomUUID(), org));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into family (id, organization_id, code, name, source) values (?, ?, 'F', 'F', 'CSV')",
                UUID.randomUUID(), org));
    }

    @Test
    void historyNeedsAnExistingResourceAndResourcesAnExistingUnit() {
        assertThrows(DataIntegrityViolationException.class, () -> cost(UUID.randomUUID(), "1", "MANUAL"));
        Timestamp now = Timestamp.from(Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into resource (id, organization_id, code, type, name, unit_id, source, created_at, updated_at) "
                        + "values (?, ?, 'Y', 'MATERIAL', 'Y', ?, 'MANUAL', ?, ?)",
                UUID.randomUUID(), org, UUID.randomUUID(), now, now));
    }

    @Test
    void theOldMaterialTablesAreGone() {
        assertEquals(0L, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where lower(table_name) in ('material', 'price_observation')", Long.class));
    }
}

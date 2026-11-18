package org.andre.construx.modules.pricing.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V38 turns the old materials and price observations into the Catalog, keeping ids and suppliers. */
class CatalogMigrationTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID user;

    @BeforeEach
    void migrateToJustBeforeV38() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:catalog-migration-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway("37").migrate();
        user = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, user + "@example.test");
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/h2");
        if (target != null) config.target(target);
        return config.load();
    }

    private UUID organization(String name) {
        UUID org = UUID.randomUUID();
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', ?)",
                org, user, name);
        return org;
    }

    private UUID material(UUID org, String name, String unit, String category, String brand) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into material (id, organization_id, name, unit, category, brand, normalized_key) "
                + "values (?, ?, ?, ?, ?, ?, ?)", id, org, name, unit, category, brand, id.toString());
        return id;
    }

    private UUID supplier(UUID org, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, ?, ?)",
                id, org, name, name.toLowerCase());
        return id;
    }

    private UUID observe(UUID org, UUID material, UUID supplier, String price, String day, String createdAt,
                         String source, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into price_observation (id, organization_id, material_id, supplier_id, price, observed_on, "
                        + "sku, source, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, org, material, supplier,
                new BigDecimal(price), Date.valueOf(day), sku, source, Timestamp.from(Instant.parse(createdAt)));
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void materialsBecomeMaterialResourcesWithTheirIdsAndGeneratedCodes() {
        UUID acme = organization("Acme");
        UUID varilla = material(acme, "Varilla", "PIECE", "STEEL", "Deacero");
        UUID cemento = material(acme, "Cemento", "BAG", "CEMENT_AGGREGATES", null);
        UUID arena = material(acme, "Arena", "CUBIC_METER", "CEMENT_AGGREGATES", null);
        jdbc.update("update material set archived_at = ? where id = ?", Timestamp.from(Instant.parse("2026-10-05T00:00:00Z")), arena);

        flyway(null).migrate();

        assertEquals(List.of("MAT-000001", "MAT-000002", "MAT-000003"), jdbc.queryForList(
                "select code from resource where organization_id = ? order by code", String.class, acme));
        Map<String, Object> row = jdbc.queryForMap("select r.*, u.code as unit_code, f.code as family_code from resource r "
                + "join unit u on u.id = r.unit_id left join family f on f.id = r.family_id where r.id = ?", varilla);
        assertEquals("MAT-000003", row.get("code"));
        assertEquals("MATERIAL", row.get("type"));
        assertEquals("Varilla", row.get("name"));
        assertEquals("Deacero", row.get("brand"));
        assertEquals("PZA", row.get("unit_code"));
        assertEquals("STEEL", row.get("family_code"));
        assertEquals("MANUAL", row.get("source"));
        assertEquals(false, row.get("is_composite"));
        assertNull(row.get("current_cost"));
        assertNotNull(row.get("created_at"));
        assertEquals("MAT-000002", jdbc.queryForObject("select code from resource where id = ?", String.class, cemento));
        assertEquals("SACO", jdbc.queryForObject("select u.code from resource r join unit u on u.id = r.unit_id where r.id = ?",
                String.class, cemento));
        assertNotNull(jdbc.queryForObject("select archived_at from resource where id = ?", Timestamp.class, arena));
        assertEquals("M3", jdbc.queryForObject("select u.code from resource r join unit u on u.id = r.unit_id where r.id = ?",
                String.class, arena));
    }

    @Test
    void everyOrganizationWithACatalogGetsTheSevenUnitsAndTheSixFamilies() {
        UUID acme = organization("Acme");
        material(acme, "Cemento", "BAG", "CEMENT_AGGREGATES", null);
        UUID suppliersOnly = organization("Depot buyer");
        supplier(suppliersOnly, "Depot");
        UUID empty = organization("Empty");

        flyway(null).migrate();

        assertEquals(1, count("select count(*) from catalog where organization_id = ?", acme));
        assertEquals("MXN", jdbc.queryForObject("select currency from catalog where organization_id = ?", String.class, acme));
        assertEquals(1, count("select count(*) from catalog where organization_id = ?", suppliersOnly));
        assertEquals(0, count("select count(*) from catalog where organization_id = ?", empty));
        assertEquals(List.of("KG", "LT", "M", "M2", "M3", "PZA", "SACO"), jdbc.queryForList(
                "select code from unit where organization_id = ? order by code", String.class, acme));
        assertEquals("Metro cúbico", jdbc.queryForObject(
                "select description from unit where organization_id = ? and code = 'M3'", String.class, acme));
        assertEquals(List.of("SEED"), jdbc.queryForList(
                "select distinct source from unit where organization_id = ?", String.class, acme));
        assertEquals(List.of("CEMENT_AGGREGATES", "ELECTRICAL", "FINISHES", "PLUMBING", "STEEL", "WOOD"), jdbc.queryForList(
                "select code from family where organization_id = ? order by code", String.class, acme));
        assertEquals("Plomería", jdbc.queryForObject(
                "select name from family where organization_id = ? and code = 'PLUMBING'", String.class, acme));
        assertEquals(7, count("select count(*) from unit where organization_id = ?", suppliersOnly));
        assertEquals(6, count("select count(*) from family where organization_id = ?", suppliersOnly));
    }

    @Test
    void observationsBecomeCostHistoryAndTheLatestOneIsTheCurrentCost() {
        UUID acme = organization("Acme");
        UUID cemento = material(acme, "Cemento", "BAG", "CEMENT_AGGREGATES", null);
        UUID depot = supplier(acme, "Depot");
        UUID ferre = supplier(acme, "Ferre");
        UUID old = observe(acme, cemento, depot, "230.00", "2026-09-01", "2026-09-01T18:00:00Z", "CSV", "C-50");
        observe(acme, cemento, ferre, "250.00", "2026-10-01", "2026-10-01T15:00:00Z", "MANUAL", null);
        UUID latest = observe(acme, cemento, depot, "245.50", "2026-10-01", "2026-10-01T16:00:00Z", "MANUAL", null);

        flyway(null).migrate();

        assertEquals(3, count("select count(*) from resource_cost_history where resource_id = ?", cemento));
        Map<String, Object> row = jdbc.queryForMap("select * from resource_cost_history where id = ?", old);
        assertEquals(acme, row.get("organization_id"));
        assertEquals(new BigDecimal("230.00"), row.get("amount"));
        assertEquals("CSV", row.get("source"));
        assertEquals(depot, row.get("supplier_id"));
        assertEquals("C-50", row.get("sku"));
        assertNull(row.get("recorded_by_membership_id"));
        assertEquals(List.of(latest), jdbc.queryForList("select id from resource_cost_history where resource_id = ? "
                + "order by effective_from desc limit 1", UUID.class, cemento));
        Instant effective = jdbc.queryForObject("select effective_from from resource_cost_history where id = ?",
                Timestamp.class, latest).toInstant();
        assertTrue(effective.isAfter(Instant.parse("2026-10-01T05:59:59Z"))
                && effective.isBefore(Instant.parse("2026-10-02T06:00:00Z")), "Mexico City day of the observation");
        Map<String, Object> resource = jdbc.queryForMap("select current_cost, cost_updated_at from resource where id = ?", cemento);
        assertEquals(new BigDecimal("245.50"), resource.get("current_cost"));
        assertEquals(effective, ((java.time.OffsetDateTime) resource.get("cost_updated_at")).toInstant());
    }

    @Test
    void theOldTablesAreGoneAndSuppliersGainTheCatalogColumns() {
        UUID acme = organization("Acme");
        UUID depot = supplier(acme, "Depot");

        flyway(null).migrate();

        assertEquals(0, count("select count(*) from information_schema.tables where lower(table_name) in "
                + "('material', 'price_observation')"));
        Map<String, Object> row = jdbc.queryForMap("select * from supplier where id = ?", depot);
        assertEquals("MANUAL", row.get("source"));
        assertEquals(false, row.get("is_default"));
        assertNull(row.get("code"));
        assertNull(row.get("rfc"));
        jdbc.update("update supplier set code = 'P1' where id = ?", depot);
        UUID other = supplier(acme, "Other");
        assertThrows(Exception.class, () -> jdbc.update("update supplier set code = 'P1' where id = ?", other));
    }

    @Test
    void resourceCodesAreUniquePerOrganizationAndCostsCannotBeNegative() {
        UUID acme = organization("Acme");
        UUID cemento = material(acme, "Cemento", "BAG", "CEMENT_AGGREGATES", null);

        flyway(null).migrate();

        UUID unit = jdbc.queryForObject("select unit_id from resource where id = ?", UUID.class, cemento);
        assertThrows(Exception.class, () -> jdbc.update("insert into resource (id, organization_id, code, type, name, "
                + "unit_id, source, created_at, updated_at) values (?, ?, 'MAT-000001', 'MATERIAL', 'X', ?, 'MANUAL', "
                + "current_timestamp, current_timestamp)", UUID.randomUUID(), acme, unit));
        assertThrows(Exception.class, () -> jdbc.update("insert into resource_cost_history (id, organization_id, "
                + "resource_id, amount, effective_from, source) values (?, ?, ?, -1, current_timestamp, 'MANUAL')",
                UUID.randomUUID(), acme, cemento));
    }
}

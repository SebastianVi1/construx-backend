package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.service.InterruptedImportRecovery;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V38 run on a database that already holds V12 rows: Flyway stops at V37, the test seeds materials, suppliers and
 * price observations, then migrates to the latest version and reads what the catalog looks like.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.target=37",
        "spring.datasource.url=jdbc:h2:mem:catalog-conversion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogConversionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    // The context starts at V37, before catalog_import exists; the startup recovery would read that table.
    @MockitoBean InterruptedImportRecovery recovery;

    private final UUID acme = UUID.randomUUID();
    private final UUID allMappings = UUID.randomUUID();
    private final UUID tieFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID tieSecond = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID brandedTie = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID sameDayEarlier = UUID.randomUUID();
    private final UUID sameDayLater = UUID.randomUUID();
    private final List<UUID> mappingIds = java.util.stream.IntStream.range(0, 7)
            .mapToObj(i -> UUID.randomUUID()).toList();
    private final UUID onlySuppliers = UUID.randomUUID();
    private final UUID empty = UUID.randomUUID();
    private final UUID varilla = UUID.randomUUID();
    private final UUID cemento = UUID.randomUUID();
    private final UUID cementoNoBrand = UUID.randomUUID();
    private final UUID depot = UUID.randomUUID();
    private final UUID mappingSupplier = UUID.randomUUID();
    private final UUID ferre = UUID.randomUUID();
    private final UUID olderObservation = UUID.randomUUID();
    private final UUID latestObservation = UUID.randomUUID();
    private final UUID varillaObservation = UUID.randomUUID();
    private final Instant archived = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeAll
    void seedV12RowsAndMigrate() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, user + "@example.test");
        for (UUID org : List.of(acme, onlySuppliers, empty, allMappings)) {
            jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', ?)",
                    org, user, "Org " + org);
        }
        material(varilla, acme, "Varilla 3/8", "METER", "STEEL", null, null);
        material(cemento, acme, "Cemento gris", "BAG", "CEMENT_AGGREGATES", "Tolteca", null);
        material(cementoNoBrand, acme, "Cemento gris", "BAG", "CEMENT_AGGREGATES", null, archived);
        supplier(depot, acme, "Depot");
        supplier(ferre, onlySuppliers, "Ferre");
        supplier(mappingSupplier, allMappings, "Mapping supplier");
        // Mexico City is UTC-6 all year since 2022; created_at's time of day is read in the session time zone.
        observe(olderObservation, cemento, "230.00", "2026-10-01", LocalDateTime.of(2026, 10, 1, 9, 30, 15), "MANUAL", null);
        observe(latestObservation, cemento, "245.50", "2026-10-02", LocalDateTime.of(2026, 10, 5, 8, 0, 0), "CSV", "CEM-50");
        observe(varillaObservation, varilla, "18.75", "2026-09-30", LocalDateTime.of(2026, 9, 30, 23, 59, 59), "MANUAL", null);

        String[] units = {"PIECE", "BAG", "SQUARE_METER", "CUBIC_METER", "KILOGRAM", "LITER", "METER"};
        String[] families = {"CEMENT_AGGREGATES", "STEEL", "WOOD", "ELECTRICAL", "PLUMBING", "FINISHES"};
        for (int i = 0; i < units.length; i++) {
            material(mappingIds.get(i), allMappings, "Mapping " + i, units[i], families[i % families.length], null, null);
        }
        // Insert opposite to id order so insertion order cannot accidentally satisfy the tie-break.
        material(brandedTie, allMappings, "Tie", "PIECE", "STEEL", "Z", null);
        material(tieSecond, allMappings, "Tie", "PIECE", "STEEL", null, null);
        material(tieFirst, allMappings, "Tie", "PIECE", "STEEL", null, null);
        observe(allMappings, sameDayLater, tieFirst, "35.00", "2026-10-03",
                LocalDateTime.of(2026, 10, 3, 18, 45, 0), "MANUAL", null);
        observe(allMappings, sameDayEarlier, tieFirst, "25.00", "2026-10-03",
                LocalDateTime.of(2026, 10, 3, 8, 15, 0), "CSV", null);

        Flyway.configure().configuration(flyway.getConfiguration()).target(MigrationVersion.LATEST).load().migrate();
    }

    private void material(UUID id, UUID org, String name, String unit, String category, String brand, Instant archivedAt) {
        jdbc.update("insert into material (id, organization_id, name, unit, category, brand, normalized_key, archived_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)", id, org, name, unit, category, brand, id.toString(),
                archivedAt == null ? null : Timestamp.from(archivedAt));
    }

    private void supplier(UUID id, UUID org, String name) {
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, ?, ?)",
                id, org, name, name.toLowerCase());
    }

    private void observe(UUID id, UUID material, String price, String day, LocalDateTime createdLocal, String source,
                         String sku) {
        observe(acme, id, material, price, day, createdLocal, source, sku);
    }

    private void observe(UUID org, UUID id, UUID material, String price, String day, LocalDateTime createdLocal,
                         String source, String sku) {
        jdbc.update("insert into price_observation (id, organization_id, material_id, supplier_id, price, observed_on, "
                        + "sku, source, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, org, material, org.equals(acme) ? depot : mappingSupplier,
                new BigDecimal(price), Date.valueOf(day), sku, source, Timestamp.valueOf(createdLocal));
    }

    private Instant instant(String sql, Object... args) {
        Timestamp value = jdbc.queryForObject(sql, Timestamp.class, args);
        return value == null ? null : value.toInstant();
    }

    @Test
    void organizationsWithMaterialsOrSuppliersGetACatalog() {
        assertEquals(List.of("MXN"), jdbc.queryForList("select currency from catalog where organization_id = ?",
                String.class, acme));
        assertEquals(1L, jdbc.queryForObject("select count(*) from catalog where organization_id = ?", Long.class,
                onlySuppliers));
        assertEquals(0L, jdbc.queryForObject("select count(*) from catalog where organization_id = ?", Long.class, empty));
    }

    @Test
    void catalogOrganizationsGetTheSeededUnitsAndFamilies() {
        for (UUID org : List.of(acme, onlySuppliers)) {
            assertEquals(List.of("KG", "LT", "M", "M2", "M3", "PZA", "SACO"), jdbc.queryForList(
                    "select code from unit where organization_id = ? and source = 'SEED' order by code", String.class, org));
            assertEquals(List.of("CEMENT_AGGREGATES", "ELECTRICAL", "FINISHES", "PLUMBING", "STEEL", "WOOD"),
                    jdbc.queryForList("select code from family where organization_id = ? and source = 'SEED' order by code",
                            String.class, org));
        }
        assertEquals("Metro cúbico", jdbc.queryForObject(
                "select description from unit where organization_id = ? and code = 'M3'", String.class, acme));
        assertEquals("Plomería", jdbc.queryForObject(
                "select name from family where organization_id = ? and code = 'PLUMBING'", String.class, acme));
        assertEquals(0L, jdbc.queryForObject("select count(*) from unit where organization_id = ?", Long.class, empty));
    }

    @Test
    void materialsBecomeMaterialResourcesKeepingTheirIds() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.code, r.type, r.name, r.brand, r.source, u.code as unit, f.code as family, r.archived_at
                from resource r join unit u on u.id = r.unit_id left join family f on f.id = r.family_id
                where r.organization_id = ? order by r.code""", acme);
        assertEquals(3, rows.size());
        // Ordered by name, brand nulls first, id.
        assertEquals(List.of(cementoNoBrand, cemento, varilla), rows.stream().map(r -> r.get("id")).toList());
        assertEquals(List.of("MAT-000001", "MAT-000002", "MAT-000003"), rows.stream().map(r -> r.get("code")).toList());
        assertEquals(List.of("SACO", "SACO", "M"), rows.stream().map(r -> r.get("unit")).toList());
        assertEquals(List.of("CEMENT_AGGREGATES", "CEMENT_AGGREGATES", "STEEL"),
                rows.stream().map(r -> r.get("family")).toList());
        assertEquals("Tolteca", rows.get(1).get("brand"));
        for (Map<String, Object> row : rows) {
            assertEquals("MATERIAL", row.get("type"));
            assertEquals("MANUAL", row.get("source"));
        }
        assertEquals(archived, instant("select archived_at from resource where id = ?", cementoNoBrand));
        assertNull(rows.get(1).get("archived_at"));
    }

    @Test
    void observationsBecomeCostHistoryAtTheirDayInMexicoCity() {
        Map<String, Object> older = jdbc.queryForMap("select * from resource_cost_history where id = ?", olderObservation);
        assertEquals(cemento, older.get("resource_id"));
        assertEquals(acme, older.get("organization_id"));
        assertEquals(new BigDecimal("230.00"), older.get("amount"));
        assertEquals("MANUAL", older.get("source"));
        assertEquals(depot, older.get("supplier_id"));
        assertNull(older.get("recorded_by_membership_id"));
        assertNull(older.get("note"));
        assertEquals(Instant.parse("2026-10-01T15:30:15Z"),
                instant("select effective_from from resource_cost_history where id = ?", olderObservation));

        Map<String, Object> latest = jdbc.queryForMap("select * from resource_cost_history where id = ?", latestObservation);
        assertEquals("CSV", latest.get("source"));
        assertEquals("CEM-50", latest.get("sku"));
        assertEquals(Instant.parse("2026-10-02T14:00:00Z"),
                instant("select effective_from from resource_cost_history where id = ?", latestObservation));
        assertEquals(Instant.parse("2026-10-01T05:59:59Z"),
                instant("select effective_from from resource_cost_history where id = ?", varillaObservation));
    }

    @Test
    void currentCostIsTheLatestHistoryEntry() {
        assertEquals(new BigDecimal("245.50"), jdbc.queryForObject(
                "select current_cost from resource where id = ?", BigDecimal.class, cemento));
        assertEquals(Instant.parse("2026-10-02T14:00:00Z"), instant("select cost_updated_at from resource where id = ?", cemento));
        assertEquals(new BigDecimal("18.75"), jdbc.queryForObject(
                "select current_cost from resource where id = ?", BigDecimal.class, varilla));
        assertNull(jdbc.queryForObject("select current_cost from resource where id = ?", BigDecimal.class, cementoNoBrand));
        assertNull(instant("select cost_updated_at from resource where id = ?", cementoNoBrand));
    }

    @Test
    void suppliersAreKeptAsManualAndTheOldTablesAreDropped() {
        assertEquals(List.of("MANUAL"), jdbc.queryForList("select distinct source from supplier", String.class));
        assertEquals(2L, jdbc.queryForObject("select count(*) from supplier where is_default = false and code is null and organization_id in (?, ?)",
                Long.class, acme, onlySuppliers));
        assertEquals(0L, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where lower(table_name) in ('material', 'price_observation')", Long.class));
    }

    @Test
    void everyLegacyUnitAndFamilyMapsToTheSeededRowAndKeepsItsMaterialId() {
        String[] units = {"PZA", "SACO", "M2", "M3", "KG", "LT", "M"};
        String[] descriptions = {"Pieza", "Saco", "Metro cuadrado", "Metro cúbico", "Kilogramo", "Litro", "Metro"};
        String[] families = {"CEMENT_AGGREGATES", "STEEL", "WOOD", "ELECTRICAL", "PLUMBING", "FINISHES"};
        String[] familyNames = {"Cemento y agregados", "Acero", "Madera", "Eléctrico", "Plomería", "Acabados"};
        for (int i = 0; i < mappingIds.size(); i++) {
            Map<String, Object> row = jdbc.queryForMap("""
                    select r.id, u.code as unit_code, u.description, f.code as family_code, f.name as family_name
                    from resource r join unit u on u.id = r.unit_id and u.organization_id = r.organization_id
                    join family f on f.id = r.family_id and f.organization_id = r.organization_id
                    where r.organization_id = ? and r.id = ?""", allMappings, mappingIds.get(i));
            assertEquals(mappingIds.get(i), row.get("id"));
            assertEquals(units[i], row.get("unit_code"));
            assertEquals(descriptions[i], row.get("description"));
            assertEquals(families[i % 6], row.get("family_code"));
            assertEquals(familyNames[i % 6], row.get("family_name"));
        }
    }

    @Test
    void generatedCodesBreakEqualNameAndBrandTiesByIdWithNullBrandsFirst() {
        assertEquals(List.of(tieFirst, tieSecond, brandedTie), jdbc.queryForList(
                "select id from resource where organization_id = ? and name = 'Tie' order by code", UUID.class, allMappings));
        assertEquals(List.of("MAT-000008", "MAT-000009", "MAT-000010"), jdbc.queryForList(
                "select code from resource where organization_id = ? and name = 'Tie' order by code", String.class, allMappings));
    }

    @Test
    void sameDayObservationsKeepCreatedAtOrderAndTheLatestBecomesCurrentCost() {
        assertEquals(List.of(sameDayEarlier, sameDayLater), jdbc.queryForList(
                "select id from resource_cost_history where resource_id = ? order by effective_from", UUID.class, tieFirst));
        assertEquals(Instant.parse("2026-10-03T14:15:00Z"),
                instant("select effective_from from resource_cost_history where id = ?", sameDayEarlier));
        assertEquals(Instant.parse("2026-10-04T00:45:00Z"),
                instant("select effective_from from resource_cost_history where id = ?", sameDayLater));
        assertEquals(new BigDecimal("35.00"), jdbc.queryForObject(
                "select current_cost from resource where id = ?", BigDecimal.class, tieFirst));
        assertEquals(Instant.parse("2026-10-04T00:45:00Z"),
                instant("select cost_updated_at from resource where id = ?", tieFirst));
    }

}

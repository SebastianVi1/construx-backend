package org.andre.construx.modules.pricing.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Constraints of V12 on a schema built only by Flyway (the flow tests use Hibernate's create-drop schema). */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:pricing-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PricingMigrationTest {
    @Autowired JdbcTemplate jdbc;

    private UUID org;
    private UUID material;
    private UUID supplier;

    @BeforeEach
    void setUp() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, user + "@example.test");
        org = UUID.randomUUID();
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', 'Acme')",
                org, user);
        material = UUID.randomUUID();
        jdbc.update("insert into material (id, organization_id, name, unit, category, normalized_key) "
                + "values (?, ?, 'Cemento', 'BAG', 'CEMENT_AGGREGATES', 'cemento|BAG|')", material, org);
        supplier = UUID.randomUUID();
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'Depot', 'depot|')",
                supplier, org);
    }

    private void observe(String price, String source) {
        jdbc.update("insert into price_observation (id, organization_id, material_id, supplier_id, price, observed_on, "
                        + "source, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), org, material,
                supplier, new BigDecimal(price), Date.valueOf("2026-10-01"), source, Timestamp.from(Instant.now()));
    }

    @Test
    void normalizedKeysAreUniquePerOrganization() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into material (id, organization_id, name, unit, category, normalized_key) "
                        + "values (?, ?, 'CEMENTO', 'BAG', 'STEEL', 'cemento|BAG|')", UUID.randomUUID(), org));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'DEPOT', 'depot|')",
                UUID.randomUUID(), org));
    }

    @Test
    void enumColumnsAndPriceAreChecked() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into material (id, organization_id, name, unit, category, normalized_key) "
                        + "values (?, ?, 'X', 'TON', 'STEEL', 'x|TON|')", UUID.randomUUID(), org));
        assertThrows(DataIntegrityViolationException.class, () -> observe("0", "MANUAL"));
        assertThrows(DataIntegrityViolationException.class, () -> observe("-1", "MANUAL"));
        assertThrows(DataIntegrityViolationException.class, () -> observe("1", "SCRAPER"));
        assertDoesNotThrow(() -> observe("245.50", "CSV"));
    }

    @Test
    void observationsNeedAnExistingMaterial() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into price_observation (id, organization_id, material_id, supplier_id, price, observed_on, "
                        + "source, created_at) values (?, ?, ?, ?, 1, ?, 'MANUAL', ?)", UUID.randomUUID(), org,
                UUID.randomUUID(), supplier, Date.valueOf("2026-10-01"), Timestamp.from(Instant.now())));
    }
}

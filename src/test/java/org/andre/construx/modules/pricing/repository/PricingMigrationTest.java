package org.andre.construx.modules.pricing.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pricing schema built only by Flyway, with Hibernate validating the entities against it
 * (the flow tests use Hibernate's create-drop schema).
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:pricing-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PricingMigrationTest {
    @Autowired JdbcTemplate jdbc;

    private UUID org;
    private UUID unit;

    @BeforeEach
    void setUp() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, user + "@example.test");
        org = UUID.randomUUID();
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', 'Acme')",
                org, user);
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'Depot', 'depot|')",
                UUID.randomUUID(), org);
        unit = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, source) values (?, ?, 'M3', 'MANUAL')", unit, org);
    }

    private void resource(String type, String source) {
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, source, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'X', ?, ?, current_timestamp, current_timestamp)",
                UUID.randomUUID(), org, UUID.randomUUID().toString().substring(0, 8), type, unit, source);
    }

    @Test
    void supplierKeysStayUniquePerOrganization() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'DEPOT', 'depot|')",
                UUID.randomUUID(), org));
    }

    @Test
    void enumColumnsAreChecked() {
        assertDoesNotThrow(() -> resource("WORK_ITEM", "NEODATA_CONSTRUBASE"));
        assertThrows(DataIntegrityViolationException.class, () -> resource("TOOL", "MANUAL"));
        assertThrows(DataIntegrityViolationException.class, () -> resource("MATERIAL", "SEED"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into unit (id, organization_id, code, source) values (?, ?, 'KG', 'CSV')", UUID.randomUUID(), org));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into unit (id, organization_id, code, source) values (?, ?, 'M3', 'MANUAL')", UUID.randomUUID(), org));
    }

    @Test
    void resourcesNeedAnExistingUnit() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into resource (id, organization_id, code, type, name, unit_id, source, created_at, updated_at) "
                        + "values (?, ?, 'A', 'MATERIAL', 'X', ?, 'MANUAL', current_timestamp, current_timestamp)",
                UUID.randomUUID(), org, UUID.randomUUID()));
    }
}

package org.andre.construx.modules.finance.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The migration of the expense-from-catalog slice keeps Resource, quantity and unit cost together (§1.12). */
class ExpenseFromCatalogMigrationTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrate() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:expense-catalog-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/h2").load()
                .migrate();
        jdbc.execute("set referential_integrity false");
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    private void expense(UUID resourceId, String quantity, String unitCost) {
        jdbc.update("insert into expense (id, project_id, amount, spent_on, description, category, created_at, "
                        + "resource_id, quantity, unit_cost) values (?, ?, 10, current_date, 'x', 'MATERIALS', "
                        + "current_timestamp, ?, cast(? as numeric(18,6)), cast(? as numeric(14,2)))",
                UUID.randomUUID(), UUID.randomUUID(), resourceId, quantity, unitCost);
    }

    @Test
    void resourceQuantityAndUnitCostAreAllSetOrAllNull() {
        UUID resource = UUID.randomUUID();
        assertDoesNotThrow(() -> expense(null, null, null));
        assertDoesNotThrow(() -> expense(resource, "2", "5.00"));
        assertThrows(DataIntegrityViolationException.class, () -> expense(resource, null, "5.00"));
        assertThrows(DataIntegrityViolationException.class, () -> expense(resource, "2", null));
        assertThrows(DataIntegrityViolationException.class, () -> expense(null, "2", "5.00"));
    }
}

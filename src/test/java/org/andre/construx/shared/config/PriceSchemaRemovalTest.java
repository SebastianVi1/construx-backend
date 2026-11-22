package org.andre.construx.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceSchemaRemovalTest {
    private static final Set<String> PRICING_TABLES = Set.of(
            "catalog", "resource", "resource_cost_history", "unit", "family", "supplier");
    private static final List<String> PRESERVED_TABLES = List.of("\"user-account\"", "organization", "organization_role",
            "organization_membership", "role_permission", "project", "document_folder", "document_entry");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createDatabase() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:price-removal-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    @Test
    void removesPopulatedPricingTablesWhilePreservingOrganizationAndDocuments() {
        flyway("8").migrate();
        // H2 assigns a generated name to V1's inline check; mirror PostgreSQL's name before V11 replaces it.
        String check = jdbc.queryForObject("select constraint_name from information_schema.check_constraints "
                + "where check_clause like '%ASSIGN_ACTIVITY%'", String.class);
        jdbc.execute("alter table role_permission rename constraint \"" + check + "\" to role_permission_action_check");
        flyway("38").migrate();

        UUID user = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, email_verified, status) values (?, ?, true, 'ACTIVE')",
                user, "owner@example.test");
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, 'ACTIVE', 'Acme')",
                organization, user);
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "values (?, ?, 'OWNER', 'Owner', true, false)", role, organization);
        jdbc.update("insert into organization_membership (id, organization_id, role_id, user_account_id, status) "
                + "values (?, ?, ?, ?, 'ACTIVE')", membership, organization, role, user);
        for (String action : List.of("VIEW_PRICES", "MANAGE_PRICES")) {
            jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, 'ORGANIZATION')",
                    UUID.randomUUID(), role, action);
        }
        jdbc.update("insert into project (id, organization_id, name, allocated_budget) values (?, ?, 'Site', 5000)",
                project, organization);
        jdbc.update("insert into document_folder (id, project_id, name_en, name_es, is_standard, "
                + "requires_permission, created_at) values (?, ?, 'Financial', 'Finanzas', true, 'VIEW_PRICES', current_timestamp)",
                folder, project);
        jdbc.update("insert into document_entry (id, folder_id, project_id, organization_id, s3_key, file_name, "
                + "file_size, file_type, source_feature, created_by, created_at) "
                + "values (?, ?, ?, ?, 'documents/budget.pdf', 'budget.pdf', 120, 'application/pdf', 'manual', ?, current_timestamp)",
                document, folder, project, organization, user);
        jdbc.update("insert into catalog (id, organization_id, created_at) values (?, ?, current_timestamp)",
                UUID.randomUUID(), organization);
        jdbc.update("insert into unit (id, organization_id, code, source) values (?, ?, 'M3', 'MANUAL')", unit, organization);
        jdbc.update("insert into family (id, organization_id, code, name, source) values (?, ?, '301', 'Aggregates', 'MANUAL')",
                family, organization);
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, 'Depot', 'depot|')",
                supplier, organization);
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, family_id, supplier_id, "
                + "source, current_cost, created_at, updated_at) "
                + "values (?, ?, 'SAND', 'MATERIAL', 'Sand', ?, ?, ?, 'MANUAL', 290, current_timestamp, current_timestamp)",
                resource, organization, unit, family, supplier);
        jdbc.update("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, "
                + "source, recorded_by_membership_id, supplier_id) values (?, ?, ?, 290, current_timestamp, 'MANUAL', ?, ?)",
                UUID.randomUUID(), organization, resource, membership, supplier);

        var unrelatedTables = tables();
        unrelatedTables.removeAll(PRICING_TABLES);
        var preservedRows = PRESERVED_TABLES.stream()
                .map(table -> jdbc.queryForList("select * from " + table + " order by id"))
                .toList();

        flyway("39").migrate();

        assertEquals(unrelatedTables, tables());
        assertEquals(preservedRows, PRESERVED_TABLES.stream()
                .map(table -> jdbc.queryForList("select * from " + table + " order by id"))
                .toList());
        assertEquals("39", flyway(null).info().current().getVersion().getVersion());
    }

    @Test
    void freshDatabaseMigratesThroughRemovalWithoutPricingTables() {
        flyway("39").migrate();

        assertTrue(tables().stream().noneMatch(PRICING_TABLES::contains));
        assertTrue(tables().containsAll(Set.of("organization", "project", "role_permission", "document_folder", "document_entry")));
        assertEquals("39", flyway(null).info().current().getVersion().getVersion());
        assertEquals(0L, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where lower(table_name) in ('material', 'price_observation')", Long.class));
    }

    private Set<String> tables() {
        return new TreeSet<>(jdbc.queryForList("select lower(table_name) from information_schema.tables "
                + "where table_schema = 'PUBLIC' and lower(table_name) <> 'flyway_schema_history'", String.class));
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/h2");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}

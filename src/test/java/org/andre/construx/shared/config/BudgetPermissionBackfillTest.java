package org.andre.construx.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V13 adds the site budget column and grants the budget permissions to Owner, Administrator and Finance. */
class BudgetPermissionBackfillTest {
    private static final String BEFORE_V13 = "12";

    private final Map<String, UUID> roles = new HashMap<>();
    // One shared session: H2 binds a check constraint to the session that created it.
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID project;

    @BeforeEach
    void migrateToJustBeforeV13WithAnExistingOrganization() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:budget-backfill-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway("8").migrate();

        // Postgres names the inline V1 check "role_permission_action_check"; H2 does not, so mirror it.
        String check = jdbc.queryForObject("select constraint_name from information_schema.check_constraints "
                + "where check_clause like '%ASSIGN_ACTIVITY%'", String.class);
        jdbc.execute("alter table role_permission rename constraint \"" + check + "\" to role_permission_action_check");
        flyway(BEFORE_V13).migrate();

        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, password, email_verified, status) values (?, ?, ?, ?, ?)",
                user, "owner@acme.test", "hash", true, "ACTIVE");
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, ?, ?)",
                org, user, "ACTIVE", "Acme");
        role(org, "OWNER", true, false);
        for (String code : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE"}) {
            role(org, code, false, true);
        }
        role(org, "ESTIMATOR", false, false);
        project = UUID.randomUUID();
        jdbc.update("insert into project (id, organization_id, name) values (?, ?, ?)", project, org, "Tower");
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    @Test
    void grantsBudgetPermissionsOnlyToOwnerAdministratorAndFinance() {
        flyway(null).migrate();

        Set<String> budget = Set.of("MANAGE_BUDGET", "VIEW_BUDGET");
        assertEquals(budget, actions("OWNER"));
        assertEquals(budget, actions("ADMINISTRATOR"));
        assertEquals(Set.of(), actions("MEMBER"));
        assertEquals(Set.of(), actions("WORKER"));
        assertEquals(budget, actions("FINANCE"));
        assertEquals(Set.of(), actions("ESTIMATOR"));
        assertEquals(Set.of("ORGANIZATION"), Set.copyOf(jdbc.queryForList(
                "select scope from role_permission where action in ('VIEW_BUDGET', 'MANAGE_BUDGET')", String.class)));
    }

    @Test
    void existingSitesHaveNoBudgetAndNegativeBudgetsAreRejected() {
        flyway(null).migrate();

        assertNull(jdbc.queryForObject("select allocated_budget from project where id = ?", BigDecimal.class, project));
        jdbc.update("update project set allocated_budget = ? where id = ?", new BigDecimal("1500.25"), project);
        assertEquals(new BigDecimal("1500.25"),
                jdbc.queryForObject("select allocated_budget from project where id = ?", BigDecimal.class, project));
        assertThrows(Exception.class, () -> jdbc.update(
                "update project set allocated_budget = ? where id = ?", new BigDecimal("-1"), project));
    }

    @Test
    void v14ExpensesBelongToASiteAndRejectNonPositiveAmountsAndUnknownCategories() {
        flyway(null).migrate();

        String insert = "insert into expense (id, project_id, amount, spent_on, description, category, created_at) "
                + "values (?, ?, ?, date '2026-10-01', 'Cement', ?, current_timestamp)";
        jdbc.update(insert, UUID.randomUUID(), project, new BigDecimal("10.50"), "MATERIALS");
        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), project, BigDecimal.ZERO, "MATERIALS"));
        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), project, BigDecimal.ONE, "FOOD"));
        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "OTHER"));
    }

    @Test
    void keepsThePreviousActionsAndStillRejectsUnknownOnes() {
        flyway(null).migrate();

        assertDoesNotThrow(() -> jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, ?)",
                UUID.randomUUID(), roles.get("ESTIMATOR"), "VIEW_PRICES", "ORGANIZATION"));
        assertThrows(Exception.class, () -> jdbc.update(
                "insert into role_permission (id, role_id, action, scope) values (?, ?, ?, ?)",
                UUID.randomUUID(), roles.get("WORKER"), "FLY", "ORGANIZATION"));
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private void role(UUID org, String code, boolean system, boolean seeded) {
        UUID id = UUID.randomUUID();
        roles.put(code, id);
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "values (?, ?, ?, ?, ?, ?)", id, org, code, code, system, seeded);
    }

    private Set<String> actions(String code) {
        return new TreeSet<>(jdbc.queryForList(
                "select action from role_permission where role_id = ? and action like '%BUDGET'", String.class,
                roles.get(code)));
    }
}

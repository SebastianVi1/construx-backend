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

/**
 * V13 adds the site budget column and grants the budget permissions to Owner, Administrator and Finance.
 * V15 takes MANAGE_BUDGET back from Administrator and lets Finance see phases and tasks. V16 adds phase and
 * task budgets.
 */
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
        flyway("13").migrate();

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
    void v15LeavesBudgetChangesToOwnerAndFinanceAndLetsFinanceSeePhasesAndTasks() {
        UUID custom = UUID.randomUUID();
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "select ?, organization_id, 'ACCOUNTANT', 'Accountant', false, false from organization_role "
                + "where id = ?", custom, roles.get("OWNER"));
        roles.put("ACCOUNTANT", custom);
        flyway("14").migrate();
        jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, 'MANAGE_BUDGET', 'ORGANIZATION')",
                UUID.randomUUID(), custom);
        grant("FINANCE", "VIEW_GOAL");

        flyway(null).migrate();

        assertEquals(Set.of("MANAGE_BUDGET", "VIEW_BUDGET"), actions("OWNER"));
        assertEquals(Set.of("VIEW_BUDGET"), actions("ADMINISTRATOR"));
        assertEquals(Set.of("MANAGE_BUDGET", "VIEW_BUDGET"), actions("FINANCE"));
        assertEquals(Set.of("MANAGE_BUDGET"), actions("ACCOUNTANT"));
        assertEquals(1, count("FINANCE", "VIEW_GOAL"));
        assertEquals(1, count("FINANCE", "VIEW_ACTIVITY"));
        assertEquals(0, count("MEMBER", "VIEW_BUDGET"));
    }

    @Test
    void v16PhaseAndTaskBudgetsAreOptionalAndCannotBeNegative() {
        flyway(null).migrate();

        UUID goal = UUID.randomUUID();
        jdbc.update("insert into goal (id, project_id, name, start_at, due_at) values (?, ?, 'Foundations', "
                + "current_timestamp, current_timestamp)", goal, project);
        assertNull(jdbc.queryForObject("select allocated_budget from goal where id = ?", BigDecimal.class, goal));
        jdbc.update("update goal set allocated_budget = ? where id = ?", new BigDecimal("10.50"), goal);
        assertThrows(Exception.class, () -> jdbc.update(
                "update goal set allocated_budget = ? where id = ?", new BigDecimal("-1"), goal));

        UUID org = jdbc.queryForObject("select organization_id from project where id = ?", UUID.class, project);
        UUID user = jdbc.queryForObject("select created_by_id from organization where id = ?", UUID.class, org);
        UUID membership = UUID.randomUUID();
        jdbc.update("insert into organization_membership (id, organization_id, role_id, user_account_id, status) "
                + "values (?, ?, ?, ?, 'ACTIVE')", membership, org, roles.get("OWNER"), user);
        UUID access = UUID.randomUUID();
        jdbc.update("insert into project_access (id, organization_membership_id, project_id) values (?, ?, ?)",
                access, membership, project);
        UUID activity = UUID.randomUUID();
        jdbc.update("insert into activity (id, goal_id, assignee_access_id, name, status, start_at, due_at) "
                + "values (?, ?, ?, 'Dig', 'TODO', current_timestamp, current_timestamp)", activity, goal, access);
        assertNull(jdbc.queryForObject("select allocated_budget from activity where id = ?", BigDecimal.class, activity));
        jdbc.update("update activity set allocated_budget = ? where id = ?", new BigDecimal("5.25"), activity);
        assertThrows(Exception.class, () -> jdbc.update(
                "update activity set allocated_budget = ? where id = ?", new BigDecimal("-1"), activity));
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

    private void grant(String code, String action) {
        jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, 'ORGANIZATION')",
                UUID.randomUUID(), roles.get(code), action);
    }

    private int count(String code, String action) {
        return jdbc.queryForObject("select count(*) from role_permission where role_id = ? and action = ?",
                Integer.class, roles.get(code), action);
    }

    private Set<String> actions(String code) {
        return new TreeSet<>(jdbc.queryForList(
                "select action from role_permission where role_id = ? and action like '%BUDGET'", String.class,
                roles.get(code)));
    }
}

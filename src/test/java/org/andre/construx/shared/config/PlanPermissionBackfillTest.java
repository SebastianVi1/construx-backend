package org.andre.construx.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V20 grants the plan permissions to roles that existed before it, like new organizations get them. */
class PlanPermissionBackfillTest {
    private static final String BEFORE_V20 = "19";

    private final Map<String, UUID> roles = new HashMap<>();
    // One shared session: H2 binds a check constraint to the session that created it.
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateToJustBeforeV20WithAnExistingOrganization() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:plan-backfill-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway("8").migrate();

        // Postgres names the inline V1 check "role_permission_action_check"; H2 does not, so mirror it.
        String check = jdbc.queryForObject("select constraint_name from information_schema.check_constraints "
                + "where check_clause like '%ASSIGN_ACTIVITY%'", String.class);
        jdbc.execute("alter table role_permission rename constraint \"" + check + "\" to role_permission_action_check");
        flyway(BEFORE_V20).migrate();

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
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    @Test
    void grantsPlanPermissionsToOwnerAndTheSeededRolesThatHaveThemAtTheirScope() {
        flyway(null).migrate();

        assertEquals(Map.of("MANAGE_PLAN", "ORGANIZATION", "VIEW_PLAN", "ORGANIZATION"), grants("OWNER"));
        assertEquals(Map.of("MANAGE_PLAN", "ORGANIZATION", "VIEW_PLAN", "ORGANIZATION"), grants("ADMINISTRATOR"));
        assertEquals(Map.of("MANAGE_PLAN", "PROJECT", "VIEW_PLAN", "PROJECT"), grants("MEMBER"));
        assertEquals(Map.of("VIEW_PLAN", "PROJECT"), grants("WORKER"));
        assertEquals(Map.of(), grants("FINANCE"));
        assertEquals(Map.of(), grants("ESTIMATOR"));
    }

    @Test
    void acceptsPlanActionsAndStillRejectsUnknownOnes() {
        flyway(null).migrate();

        assertDoesNotThrow(() -> jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, ?)",
                UUID.randomUUID(), roles.get("FINANCE"), "VIEW_PLAN", "ORGANIZATION"));
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

    private Map<String, String> grants(String code) {
        Map<String, String> grants = new java.util.TreeMap<>();
        jdbc.query("select action, scope from role_permission where role_id = ? and action in ('VIEW_PLAN', 'MANAGE_PLAN')",
                rs -> { grants.put(rs.getString(1), rs.getString(2)); }, roles.get(code));
        return grants;
    }
}

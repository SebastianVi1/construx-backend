package org.andre.construx.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V11 grants the price permissions to roles that existed before it, like new organizations get them. */
class PricePermissionBackfillTest {
    /** V9 (start dates) and V10 (bar colors) land with the phases-gantt work on master; V8 is the last shared migration before V11. */
    private static final String BEFORE_V11 = "8";

    private final Map<String, UUID> roles = new HashMap<>();
    // One shared session: H2 binds a check constraint to the session that created it.
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateToJustBeforeV11WithAnExistingOrganization() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:price-backfill-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway(BEFORE_V11).migrate();

        // Postgres names the inline V1 check "role_permission_action_check"; H2 does not, so mirror it.
        String check = jdbc.queryForObject("select constraint_name from information_schema.check_constraints "
                + "where check_clause like '%ASSIGN_ACTIVITY%'", String.class);
        jdbc.execute("alter table role_permission rename constraint \"" + check + "\" to role_permission_action_check");

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
        jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, ?)",
                UUID.randomUUID(), roles.get("OWNER"), "VIEW_PROJECT", "ORGANIZATION");
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    @Test
    void grantsPricePermissionsOnlyToOwnerAndTheSeededRolesThatHaveThem() {
        flyway(null).migrate();

        assertEquals(Set.of("MANAGE_PRICES", "VIEW_PRICES", "VIEW_PROJECT"), actions("OWNER"));
        assertEquals(Set.of("MANAGE_PRICES", "VIEW_PRICES"), actions("ADMINISTRATOR"));
        assertEquals(Set.of("VIEW_PRICES"), actions("MEMBER"));
        assertEquals(Set.of(), actions("WORKER"));
        assertEquals(Set.of("MANAGE_PRICES", "VIEW_PRICES"), actions("FINANCE"));
        assertEquals(Set.of(), actions("ESTIMATOR"));
        assertEquals(Set.of("ORGANIZATION"), Set.copyOf(jdbc.queryForList(
                "select scope from role_permission where action in ('VIEW_PRICES', 'MANAGE_PRICES')", String.class)));
    }

    @Test
    void stillRejectsUnknownActions() {
        flyway(null).migrate();

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
                "select action from role_permission where role_id = ?", String.class, roles.get(code)));
    }
}

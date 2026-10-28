package org.andre.construx.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V17 turns each task's single assignee into the Lead of its team and drops the old column. */
class ActivityTeamMigrationTest {
    // One shared session: H2 binds a check constraint to the session that created it.
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID goal, ana, bob, pour, cure;

    @BeforeEach
    void tasksWithOneAssigneeEachBeforeV17() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:activity-team-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway("8").migrate();
        // Postgres names the inline V1 check "role_permission_action_check"; H2 does not, so mirror it.
        String check = jdbc.queryForObject("select constraint_name from information_schema.check_constraints "
                + "where check_clause like '%ASSIGN_ACTIVITY%'", String.class);
        jdbc.execute("alter table role_permission rename constraint \"" + check + "\" to role_permission_action_check");
        flyway("16").migrate();

        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, password, email_verified, status) values (?, ?, ?, ?, ?)",
                user, "owner@acme.test", "hash", true, "ACTIVE");
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, ?, ?)",
                org, user, "ACTIVE", "Acme");
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "values (?, ?, 'OWNER', 'Owner', true, false)", role, org);
        jdbc.update("insert into project (id, organization_id, name) values (?, ?, ?)", project, org, "Tower");
        goal = UUID.randomUUID();
        jdbc.update("insert into goal (id, project_id, name, start_at, due_at) values (?, ?, 'Foundations', "
                + "current_timestamp, current_timestamp)", goal, project);
        ana = access(org, role, project, user);
        UUID other = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, password, email_verified, status) values (?, ?, ?, ?, ?)",
                other, "bob@acme.test", "hash", true, "ACTIVE");
        bob = access(org, role, project, other);
        pour = task("Pour", ana);
        cure = task("Cure", bob);
    }

    @AfterEach
    void close() {
        dataSource.destroy();
    }

    @Test
    void everyExistingAssigneeBecomesTheLeadOfTheirTask() {
        flyway(null).migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select activity_id, access_id, task_role from activity_assignee order by task_role, access_id");
        assertEquals(2, rows.size());
        assertEquals(ana, jdbc.queryForObject("select access_id from activity_assignee where activity_id = ?",
                UUID.class, pour));
        assertEquals(bob, jdbc.queryForObject("select access_id from activity_assignee where activity_id = ?",
                UUID.class, cure));
        assertTrue(rows.stream().allMatch(r -> "LEAD".equals(r.get("task_role"))));
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where lower(table_name) = 'activity' and lower(column_name) = 'assignee_access_id'", Integer.class));
    }

    @Test
    void aPersonIsInATeamOnceWithAKnownRoleAndTeamsGoWithTheirTask() {
        flyway(null).migrate();

        String insert = "insert into activity_assignee (id, activity_id, access_id, task_role) values (?, ?, ?, ?)";
        jdbc.update(insert, UUID.randomUUID(), pour, bob, "CREW");
        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), pour, bob, "FOREMAN"));
        assertThrows(Exception.class, () -> jdbc.update(insert, UUID.randomUUID(), cure, ana, "BOSS"));
        jdbc.update("delete from activity where id = ?", pour);
        assertEquals(0, jdbc.queryForObject("select count(*) from activity_assignee where activity_id = ?",
                Integer.class, pour));
    }

    private UUID access(UUID org, UUID role, UUID project, UUID user) {
        UUID membership = UUID.randomUUID();
        jdbc.update("insert into organization_membership (id, organization_id, role_id, user_account_id, status) "
                + "values (?, ?, ?, ?, 'ACTIVE')", membership, org, role, user);
        UUID access = UUID.randomUUID();
        jdbc.update("insert into project_access (id, organization_membership_id, project_id) values (?, ?, ?)",
                access, membership, project);
        return access;
    }

    private UUID task(String name, UUID access) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into activity (id, goal_id, assignee_access_id, name, status, start_at, due_at) "
                + "values (?, ?, ?, ?, 'TODO', current_timestamp, current_timestamp)", id, goal, access, name);
        return id;
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
}

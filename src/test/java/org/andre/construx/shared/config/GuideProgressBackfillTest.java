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

/** V40 hides the guide for every membership that existed before it, so existing users never see it pop up. */
class GuideProgressBackfillTest {
    private static final String BEFORE_V40 = "39";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID organization, role, ownerMembership, workerMembership;

    @BeforeEach
    void migrateToJustBeforeV40WithTwoMembers() {
        dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:guide-backfill-" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(dataSource);
        flyway(BEFORE_V40).migrate();

        UUID owner = user("owner@acme.test");
        organization = UUID.randomUUID();
        jdbc.update("insert into organization (id, created_by_id, status, name) values (?, ?, ?, ?)",
                organization, owner, "ACTIVE", "Acme");
        role = UUID.randomUUID();
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "values (?, ?, ?, ?, ?, ?)", role, organization, "MEMBER", "Member", false, true);
        ownerMembership = membership(owner);
        workerMembership = membership(user("worker@acme.test"));
    }

    @AfterEach
    void closeDatabase() {
        dataSource.destroy();
    }

    @Test
    void everyExistingMembershipStartsDismissedWithNoSteps() {
        flyway(null).migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select organization_membership_id, dismissed_at, updated_at from guide_progress");
        assertEquals(2, rows.size());
        assertEquals(List.of(ownerMembership, workerMembership).stream().sorted().toList(),
                rows.stream().map(r -> (UUID) r.get("organization_membership_id")).sorted().toList());
        rows.forEach(r -> {
            assertNotNull(r.get("dismissed_at"));
            assertNotNull(r.get("updated_at"));
        });
        assertEquals(0L, jdbc.queryForObject("select count(*) from guide_progress_step", Long.class));
    }

    @Test
    void membershipsCreatedAfterTheMigrationHaveNoProgress() {
        flyway(null).migrate();

        UUID later = membership(user("later@acme.test"));

        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from guide_progress where organization_membership_id = ?", Long.class, later));
    }

    @Test
    void stepsAcceptOnlyKnownStatusesAndGoAwayWithTheMembership() {
        flyway(null).migrate();
        jdbc.update("insert into guide_progress_step (organization_membership_id, step_id, status) values (?, ?, ?)",
                workerMembership, "create-site", "COMPLETED");

        assertThrows(Exception.class, () -> jdbc.update(
                "insert into guide_progress_step (organization_membership_id, step_id, status) values (?, ?, ?)",
                workerMembership, "invite-member", "DONE"));

        jdbc.update("delete from organization_membership where id = ?", workerMembership);
        assertEquals(0L, jdbc.queryForObject("select count(*) from guide_progress where organization_membership_id = ?",
                Long.class, workerMembership));
        assertEquals(0L, jdbc.queryForObject("select count(*) from guide_progress_step", Long.class));
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/h2");
        if (target != null) config.target(target);
        return config.load();
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into \"user-account\" (id, email, password, email_verified, status) values (?, ?, ?, ?, ?)",
                id, email, "hash", true, "ACTIVE");
        return id;
    }

    private UUID membership(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization_membership (id, organization_id, role_id, user_account_id, status) "
                + "values (?, ?, ?, ?, ?)", id, organization, role, user, "ACTIVE");
        return id;
    }
}

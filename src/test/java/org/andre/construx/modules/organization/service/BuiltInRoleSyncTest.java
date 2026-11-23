package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.organization.model.BuiltInRoles;
import org.andre.construx.modules.organization.model.LocalizedText;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.model.RoleKind;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.shared.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract roles §6.2 at the sync level, with crafted definitions. Runs on its own database: the sync touches every
 * company, so crafted definitions must not reach companies other test classes created.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:built-in-role-sync;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BuiltInRoleSyncTest {
    @Autowired BuiltInRoleSync sync;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationRoleRepository roles;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired UserAccountRepository users;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;

    private TransactionTemplate tx;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txm);
        sync.sync(BuiltInRoles.definitions()); // earlier tests may have left other companies on crafted definitions
        orgId = newCompany();
    }

    @Test
    void ownerGainsEveryAction() {
        UUID owner = roleId("OWNER");
        jdbc.update("delete from role_permission where role_id = ? and action = 'VIEW_PLAN'", owner);
        long before = versions().get("OWNER");

        sync.sync(BuiltInRoles.definitions());

        assertEquals(16, grants("OWNER").size());
        assertEquals(before + 1, versions().get("OWNER"), "a sync change raises the version once");
        assertEquals(List.of("SYNC"), auditActions(owner));
    }

    @Test
    void uncustomizedRoleFollowsFile() {
        Map<PermissionAction, PermissionScope> worker = new EnumMap<>(file("WORKER").grants());
        worker.put(VIEW_PRICES, ORGANIZATION);
        worker.remove(VIEW_PLAN);
        long before = versions().get("WORKER");

        BuiltInRoleSync.SyncReport report = sync.sync(withRole("WORKER", worker));

        assertEquals(worker, grants("WORKER"));
        assertFalse(role("WORKER").isCustomized());
        assertTrue(report.synced() >= 1);
        assertEquals(List.of("SYNC"), auditActions(roleId("WORKER")));
        assertEquals(before + 1, versions().get("WORKER"), "a stale save after the sync gets ROLE_CHANGED");
    }

    @Test
    void rolesFromBeforeTheMigrationFollowFileAndLearnEveryAction() {
        UUID worker = roleId("WORKER");
        jdbc.update("delete from organization_role_known_action where role_id in "
                + "(select id from organization_role where organization_id = ?)", orgId);
        jdbc.update("delete from role_permission where role_id = ? and action = 'VIEW_PLAN'", worker);

        sync.sync(BuiltInRoles.definitions());

        assertEquals(file("WORKER").grants(), grants("WORKER"));
        assertEquals(EnumSet.allOf(PermissionAction.class), role("WORKER").knownActions());
        assertEquals(EnumSet.allOf(PermissionAction.class), role("MEMBER").knownActions());
        assertTrue(auditActions(roleId("MEMBER")).isEmpty(), "known actions alone are not audited");
    }

    @Test
    void aRoleWriteCommittingDuringTheSyncNeitherFailsItNorIsLost() throws Exception {
        UUID worker = roleId("WORKER");
        jdbc.update("delete from organization_role_known_action where role_id = ?", worker); // the sync must write it
        long before = versions().get("WORKER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // A PUT on Worker: its row locked, its version raised, not yet committed when the sync reaches it.
            Future<?> put = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from organization_role where id = ? for update", UUID.class, worker);
                jdbc.update("update organization_role set version = version + 1 where id = ?", worker);
                written.countDown();
                awaitQuietly(release);
            }));
            written.await();
            Future<BuiltInRoleSync.SyncReport> syncing = pool.submit(() -> sync.sync(BuiltInRoles.definitions()));
            try {
                syncing.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // the sync waits for the PUT's lock
            }
            release.countDown();
            put.get();

            assertDoesNotThrow(() -> syncing.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(before + 2, versions().get("WORKER"), "the PUT and the sync each raise the version once");
        assertEquals(EnumSet.allOf(PermissionAction.class), role("WORKER").knownActions());
    }

    @Test
    void customizedRoleKeepsChoicesAndGetsOnlyNewActions() {
        Map<PermissionAction, PermissionScope> chosen = new EnumMap<>(file("WORKER").grants());
        chosen.remove(VIEW_PLAN);
        chosen.put(VIEW_BUDGET, ORGANIZATION);
        edit("WORKER", r -> r.replacePermissions(chosen));
        UUID worker = roleId("WORKER");
        // As if VIEW_PRICES and MANAGE_PLAN joined the catalog after the company customized Worker.
        jdbc.update("delete from organization_role_known_action where role_id = ? and action in ('VIEW_PRICES', 'MANAGE_PLAN')",
                worker);

        Map<PermissionAction, PermissionScope> file = new EnumMap<>(file("WORKER").grants());
        file.put(VIEW_PRICES, ORGANIZATION);
        file.put(MANAGE_PLAN, PROJECT); // needs VIEW_PLAN, which the company switched off: skipped
        file.put(VIEW_GOAL, ORGANIZATION); // already known: the company's choice stays
        file.put(VIEW_PROJECT, ORGANIZATION);

        long before = versions().get("WORKER");
        assertDoesNotThrow(() -> sync.sync(withRole("WORKER", file)));

        Map<PermissionAction, PermissionScope> expected = new EnumMap<>(chosen);
        expected.put(VIEW_PRICES, ORGANIZATION);
        assertEquals(expected, grants("WORKER"));
        assertTrue(role("WORKER").isCustomized());
        assertEquals(EnumSet.allOf(PermissionAction.class), role("WORKER").knownActions());
        assertEquals(before + 1, versions().get("WORKER"));
    }

    @Test
    void missingBuiltInRoleIsCreated() {
        UUID resident = roleId("RESIDENT");
        jdbc.update("delete from role_permission where role_id = ?", resident);
        jdbc.update("delete from organization_role_known_action where role_id = ?", resident);
        jdbc.update("delete from organization_role where id = ?", resident);

        BuiltInRoleSync.SyncReport report = sync.sync(BuiltInRoles.definitions());

        assertEquals(1, report.created());
        OrganizationRole created = role("RESIDENT");
        assertEquals(RoleKind.BUILT_IN, created.kind());
        assertFalse(created.isCustomized());
        assertEquals("Residente", created.getName());
        assertEquals(file("RESIDENT").grants(), grants("RESIDENT"));
        assertEquals(EnumSet.allOf(PermissionAction.class), created.knownActions());
        assertEquals(List.of("SYNC"), auditActions(created.getId()));
    }

    @Test
    void removedBuiltInRoleBecomesCustomWithSameMembersAndIsAdoptedWhenReadded() {
        UUID resident = roleId("RESIDENT");
        UUID membership = addMember("RESIDENT");
        Map<PermissionAction, PermissionScope> before = grants("RESIDENT");
        List<BuiltInRoles.Definition> withoutResident = new ArrayList<>(BuiltInRoles.definitions());
        withoutResident.removeIf(d -> d.code().equals("RESIDENT"));

        BuiltInRoleSync.SyncReport removed = sync.sync(withoutResident);

        assertTrue(removed.converted() >= 1);
        OrganizationRole converted = role("RESIDENT");
        assertEquals(resident, converted.getId());
        assertEquals(RoleKind.CUSTOM, converted.kind());
        assertEquals("Residente", converted.getName());
        assertEquals(before, grants("RESIDENT"));
        assertEquals(resident, memberships.findById(membership).orElseThrow().getRole().getId());
        assertEquals(List.of("CONVERT_TO_CUSTOM"), auditActions(resident));
        assertNull(audit.listForEntity("ORGANIZATION_ROLE", resident).getFirst().actorMembershipId());

        BuiltInRoleSync.SyncReport readded = sync.sync(BuiltInRoles.definitions());

        assertTrue(readded.adopted() >= 1);
        OrganizationRole adopted = role("RESIDENT");
        assertEquals(RoleKind.BUILT_IN, adopted.kind());
        assertTrue(adopted.isCustomized());
        assertEquals(before, grants("RESIDENT"));
        assertEquals(resident, memberships.findById(membership).orElseThrow().getRole().getId());
        assertEquals(List.of("ADOPT", "CONVERT_TO_CUSTOM"), auditActions(resident));
    }

    @Test
    void storedNameFollowsFileUnlessItClashesWithACustomRole() {
        tx.executeWithoutResult(s -> roles.save(OrganizationRole.custom(organizations.findById(orgId).orElseThrow(),
                "Peón", null, Map.of())));
        List<BuiltInRoles.Definition> renamed = new ArrayList<>();
        for (BuiltInRoles.Definition d : BuiltInRoles.definitions()) {
            LocalizedText name = switch (d.code()) {
                case "WORKER" -> new LocalizedText("Laborer", "Peón");
                case "MEMBER" -> new LocalizedText("Office staff", "Oficinista");
                default -> d.name();
            };
            renamed.add(new BuiltInRoles.Definition(d.code(), name, d.description(), d.grants()));
        }

        assertDoesNotThrow(() -> sync.sync(renamed));

        assertEquals("Trabajador", role("WORKER").getName());
        assertEquals("Oficinista", role("MEMBER").getName());
        assertEquals(List.of("SYNC"), auditActions(roleId("MEMBER")));
        assertTrue(auditActions(roleId("WORKER")).isEmpty());
    }

    @Test
    void newCompanyMatchesTheFileAndASecondRunChangesNothing() {
        Map<String, Long> versions = versions();

        BuiltInRoleSync.SyncReport first = sync.sync(BuiltInRoles.definitions());
        BuiltInRoleSync.SyncReport second = sync.sync(BuiltInRoles.definitions());

        assertEquals(new BuiltInRoleSync.SyncReport(0, 0, 0, 0), first);
        assertEquals(new BuiltInRoleSync.SyncReport(0, 0, 0, 0), second);
        assertEquals(versions, versions());
        for (String code : versions.keySet()) {
            assertTrue(auditActions(roleId(code)).isEmpty(), code);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private UUID newCompany() {
        return tx.execute(s -> {
            UserAccount owner = users.save(new UserAccount("sync-" + UUID.randomUUID() + "@example.test", "hash"));
            return organizations.save(new Organization("Sync " + UUID.randomUUID(), owner)).getId();
        });
    }

    private UUID addMember(String roleCode) {
        return tx.execute(s -> {
            UserAccount user = users.save(new UserAccount("member-" + UUID.randomUUID() + "@example.test", "hash"));
            Organization org = organizations.findById(orgId).orElseThrow();
            OrganizationRole role = roles.findByOrganizationIdAndCode(orgId, roleCode).orElseThrow();
            return memberships.save(new OrganizationMembership(org, user, role)).getId();
        });
    }

    private void edit(String code, Consumer<OrganizationRole> change) {
        tx.executeWithoutResult(s -> change.accept(roles.findByOrganizationIdAndCode(orgId, code).orElseThrow()));
    }

    private OrganizationRole role(String code) {
        return tx.execute(s -> {
            OrganizationRole role = roles.findByOrganizationIdAndCode(orgId, code).orElseThrow();
            role.knownActions();
            role.grants();
            return role;
        });
    }

    private UUID roleId(String code) {
        return role(code).getId();
    }

    private Map<PermissionAction, PermissionScope> grants(String code) {
        return tx.execute(s -> roles.findByOrganizationIdAndCode(orgId, code).orElseThrow().grants());
    }

    private Map<String, Long> versions() {
        Map<String, Long> versions = new java.util.TreeMap<>();
        jdbc.query("select code, version from organization_role where organization_id = ?",
                rs -> { versions.put(rs.getString(1), rs.getLong(2)); }, orgId);
        return versions;
    }

    private List<String> auditActions(UUID roleId) {
        return audit.listForEntity("ORGANIZATION_ROLE", roleId).stream().map(AuditService.AuditEventData::action).toList();
    }

    private static BuiltInRoles.Definition file(String code) {
        return BuiltInRoles.find(code).orElseThrow();
    }

    private static List<BuiltInRoles.Definition> withRole(String code, Map<PermissionAction, PermissionScope> grants) {
        return BuiltInRoles.definitions().stream()
                .map(d -> d.code().equals(code) ? new BuiltInRoles.Definition(code, d.name(), d.description(), grants) : d)
                .toList();
    }
}

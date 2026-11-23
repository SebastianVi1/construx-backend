package org.andre.construx.modules.organization.controller;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.service.InvitationMailer;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Creating, renaming, editing and deleting Custom roles (contract roles §5.3, §5.4, §5.6, §7). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomRoleFlowTest {
    /** The Worker role's file defaults plus VIEW_PRICES. */
    private static final String WORKER_WITH_PRICES = """
            [{"action":"VIEW_PROJECT","scope":"PROJECT"},{"action":"VIEW_GOAL","scope":"PROJECT"},
             {"action":"VIEW_ACTIVITY","scope":"PROJECT"},{"action":"MANAGE_ACTIVITY","scope":"OWN"},
             {"action":"VIEW_PLAN","scope":"PROJECT"},{"action":"VIEW_PRICES","scope":"ORGANIZATION"}]""";
    private static final String BUDGETS = """
            [{"action":"VIEW_BUDGET","scope":"ORGANIZATION"},{"action":"MANAGE_BUDGET","scope":"ORGANIZATION"}]""";

    /** How long a held write stays open while the other request runs, unless that request finishes first. */
    private static final long HOLD = 500;

    @MockitoBean InvitationMailer mailer;

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;

    private Api api;
    private Members members;
    private Session owner, admin, worker;
    private UUID orgId, workerMembership;
    private String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");
        worker = api.signUp();
        workerMembership = members.add(orgId, worker.user(), "WORKER");
    }

    @Test
    void administratorSavesACopyOfWorkerWithPricesAndAssignsAWorkerToIt() throws Exception {
        String code = codeOf(create(admin, "Foreman with prices", "Worker who sees prices", WORKER_WITH_PRICES)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("CUSTOM_[A-Z2-7]{10}")))
                .andExpect(jsonPath("$.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.names.en").value("Foreman with prices"))
                .andExpect(jsonPath("$.names.es").value("Foreman with prices"))
                .andExpect(jsonPath("$.description.en").value("Worker who sees prices"))
                .andExpect(jsonPath("$.memberCount").value(0))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.defaults").doesNotExist())
                .andExpect(jsonPath("$.permissions[*].action").value(hasItem("VIEW_PRICES"))));

        api.get(admin, org + "/roles")
                .andExpect(jsonPath("$[-1].code").value(code))
                .andExpect(jsonPath("$[-1].kind").value("CUSTOM"))
                .andExpect(jsonPath("$[-1].memberCount").value(0));

        api.get(worker, org + "/me").andExpect(jsonPath("$.permissions[*].action").value(not(hasItem("VIEW_PRICES"))));
        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"" + code + "\"}")
                .andExpect(status().isOk());
        api.get(worker, org + "/me").andExpect(jsonPath("$.permissions[*].action").value(hasItem("VIEW_PRICES")));
    }

    @Test
    void creatingARoleWithBudgetsIsAboveAnAdministratorButNotTheOwner() throws Exception {
        create(admin, "Budget keeper", null, BUDGETS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        create(owner, "Budget keeper", null, BUDGETS).andExpect(status().isCreated());
        create(worker, "Anything", null, "[]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void createdSetsFollowThePermissionRules() throws Exception {
        create(owner, "Plans only", null, "[{\"action\":\"MANAGE_PLAN\",\"scope\":\"PROJECT\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_DEPENDENCY_MISSING"));
        create(owner, "Prices here", null, "[{\"action\":\"VIEW_PRICES\",\"scope\":\"PROJECT\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_SCOPE_INVALID"));
        create(owner, "Nothing yet", null, "[]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions.length()").value(0))
                .andExpect(jsonPath("$.description.en").doesNotExist());
    }

    @Test
    void customNameCannotReuseBuiltInNameInAnyLanguageOrCase() throws Exception {
        for (String name : List.of("worker", " TRABAJADOR ", "propietario", "Owner")) {
            create(owner, name, null, "[]")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        }
        create(owner, "Yard crew", null, "[]").andExpect(status().isCreated());
        create(owner, " yard CREW ", null, "[]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
    }

    @Test
    void namesAndDescriptionsMustFitTheirLimits() throws Exception {
        for (String name : List.of("", "   ", "x".repeat(101))) {
            create(owner, name, null, "[]")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ROLE_NAME_INVALID"));
        }
        api.post(owner, org + "/roles", "{\"permissions\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_INVALID"));
        create(owner, "Long notes", "d".repeat(281), "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_INVALID"));
        create(owner, "n".repeat(100), "d".repeat(280), "[]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("n".repeat(100)));
    }

    @Test
    void parallelCreatesCannotPassTheLimit() throws Exception {
        for (int i = 1; i <= 29; i++) {
            create(owner, "Crew " + i, null, "[]").andExpect(status().isCreated());
        }

        List<String> codes = inParallel(6, i -> "{\"name\":\"Late " + i + "\",\"permissions\":[]}");

        assertEquals(1, codes.stream().filter("201"::equals).count(), codes.toString());
        assertEquals(5, codes.stream().filter("ROLE_LIMIT_REACHED"::equals).count(), codes.toString());
        assertEquals(30, jdbc.queryForObject("select count(*) from organization_role where organization_id = ? "
                + "and seeded = false and system_role = false", Long.class, orgId));
    }

    @Test
    void parallelCreatesCannotShareANameInAnyCase() throws Exception {
        List<String> names = List.of("Yard crew", "yard crew", "YARD CREW", " Yard Crew ", "yard CREW", "Yard CREW");

        List<String> codes = inParallel(names.size(), i -> "{\"name\":\"" + names.get(i) + "\",\"permissions\":[]}");

        assertEquals(1, codes.stream().filter("201"::equals).count(), codes.toString());
        assertEquals(5, codes.stream().filter("ROLE_NAME_TAKEN"::equals).count(), codes.toString());
    }

    @Test
    void anAssignmentNeverActsOnARoleSomeoneElseJustGaveTheMember() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID member = members.add(orgId, api.signUp().user(), "WORKER");

            List<Integer> statuses = atOnce(List.of(
                    () -> api.patch(owner, org + "/members/" + member, "{\"roleCode\":\"FINANCE\"}")
                            .andReturn().getResponse().getStatus(),
                    () -> api.patch(admin, org + "/members/" + member, "{\"roleCode\":\"MEMBER\"}")
                            .andReturn().getResponse().getStatus()));

            assertEquals(200, statuses.get(0), statuses.toString());
            assertTrue(List.of(200, 403).contains(statuses.get(1)), statuses.toString());
            assertEquals("FINANCE", roleOf(member), "the Administrator moved a Finance member: " + statuses);
        }
    }

    @Test
    void anAssignmentIntoARoleBeingDeletedEndsInTheReplacement() throws Exception {
        for (int round = 0; round < 8; round++) {
            String code = codeOf(create(owner, "Short lived " + round, null, "[]"));
            UUID member = members.add(orgId, api.signUp().user(), "WORKER");

            List<Integer> statuses = atOnce(List.of(
                    () -> api.delete(owner, org + "/roles/" + code + "?replacement=MEMBER")
                            .andReturn().getResponse().getStatus(),
                    () -> api.patch(admin, org + "/members/" + member, "{\"roleCode\":\"" + code + "\"}")
                            .andReturn().getResponse().getStatus()));

            assertEquals(204, statuses.get(0), statuses.toString());
            assertTrue(List.of(200, 400).contains(statuses.get(1)), statuses.toString());
            assertEquals(statuses.get(1) == 200 ? "MEMBER" : "WORKER", roleOf(member), statuses.toString());
        }
    }

    @Test
    void aDeleteIntoAReplacementBeingRaisedAboveTheCallerWaitsAndIsRefused() throws Exception {
        String yard = codeOf(create(owner, "Yard crew", null, "[]"));
        String gate = codeOf(create(owner, "Gate crew", null, "[]"));
        UUID member = members.add(orgId, api.signUp().user(), yard);

        String outcome = whileHeld(() -> raiseToBudgets(gate),
                () -> outcomeOf(api.delete(admin, org + "/roles/" + yard + "?replacement=" + gate)));

        assertEquals("ROLE_ABOVE_CALLER", outcome);
        assertEquals(yard, roleOf(member));
    }

    @Test
    void crossingDeletesDoNotDeadlock() throws Exception {
        for (int round = 0; round < 8; round++) {
            String yard = codeOf(create(owner, "Yard " + round, null, "[]"));
            String gate = codeOf(create(owner, "Gate " + round, null, "[]"));
            UUID inYard = members.add(orgId, api.signUp().user(), yard);
            UUID inGate = members.add(orgId, api.signUp().user(), gate);

            List<String> outcomes = atOnce(List.of(
                    () -> outcomeOf(api.delete(owner, org + "/roles/" + yard + "?replacement=" + gate)),
                    () -> outcomeOf(api.delete(owner, org + "/roles/" + gate + "?replacement=" + yard))));

            assertEquals(1, outcomes.stream().filter("204"::equals).count(), outcomes.toString());
            assertTrue(outcomes.contains("ROLE_REPLACEMENT_INVALID"), outcomes.toString());
            String survivor = outcomes.get(0).equals("204") ? gate : yard;
            assertEquals(survivor, roleOf(inYard), outcomes.toString());
            assertEquals(survivor, roleOf(inGate), outcomes.toString());
        }
    }

    @Test
    void theThirtyFirstCustomRoleHitsTheLimit() throws Exception {
        for (int i = 1; i <= 30; i++) {
            create(owner, "Crew " + i, null, "[]").andExpect(status().isCreated());
        }
        create(owner, "Crew 31", null, "[]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_LIMIT_REACHED"));
    }

    @Test
    void customRoleIsRenamedAndDescribedWithItsPermissions() throws Exception {
        String code = codeOf(create(owner, "Yard crew", "Loads trucks", "[]"));
        create(owner, "Gate crew", null, "[]").andExpect(status().isCreated());
        long created = version(code);

        long renamed = versionOf(api.put(owner, org + "/roles/" + code, text("Site crew", "Keeps the yard", WORKER_WITH_PRICES,
                        created))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Site crew"))
                .andExpect(jsonPath("$.names.es").value("Site crew"))
                .andExpect(jsonPath("$.description.en").value("Keeps the yard"))
                .andExpect(jsonPath("$.permissions[*].action").value(hasItem("VIEW_PRICES"))));

        long recased = versionOf(api.put(owner, org + "/roles/" + code, text(" SITE crew ", null, "[]", renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SITE crew"))
                .andExpect(jsonPath("$.description.en").doesNotExist()));
        assertEquals(created + 1, renamed, "a rename with new permissions is one write");
        assertEquals(renamed + 1, recased);

        for (String taken : List.of("gate CREW", "Trabajador", "propietario")) {
            api.put(owner, org + "/roles/" + code, text(taken, null, "[]", recased))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ROLE_NAME_TAKEN"));
        }
        api.put(owner, org + "/roles/" + code, text(" ", null, "[]", recased))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_INVALID"));
        api.put(owner, org + "/roles/" + code, text("SITE crew", "d".repeat(281), "[]", recased))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_INVALID"));
        api.get(owner, org + "/roles/" + code)
                .andExpect(jsonPath("$.name").value("SITE crew"))
                .andExpect(jsonPath("$.version").value(recased));
    }

    @Test
    void administratorCannotEditOrDeleteACustomRoleAboveThem() throws Exception {
        String code = codeOf(create(owner, "Budget keeper", null, BUDGETS));
        api.get(admin, org + "/roles/" + code).andExpect(jsonPath("$.editable").value(false));
        api.put(admin, org + "/roles/" + code, text("Keeper", null, "[]", version(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.delete(admin, org + "/roles/" + code)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        String mine = codeOf(create(admin, "Helpers", null, "[]"));
        api.put(admin, org + "/roles/" + mine, text("Helpers", null, BUDGETS, version(mine)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.delete(worker, org + "/roles/" + mine)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteNeedsAReplacementWhileMembersHoldTheRoleThenMovesThemAndTheInvitations() throws Exception {
        String code = codeOf(create(owner, "Yard crew", null, "[]"));
        List<UUID> held = List.of(members.add(orgId, api.signUp().user(), code),
                members.add(orgId, api.signUp().user(), code), members.add(orgId, api.signUp().user(), code));
        api.post(owner, org + "/invitations", "{\"email\":\"new-" + UUID.randomUUID() + "@example.com\",\"roleCode\":\""
                + code + "\"}").andExpect(status().isCreated());

        api.delete(owner, org + "/roles/" + code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_REPLACEMENT_REQUIRED"))
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.formerMemberCount").value(0))
                .andExpect(jsonPath("$.pendingInvitationCount").value(1));
        api.get(owner, org + "/roles/" + code).andExpect(status().isOk());

        api.delete(owner, org + "/roles/" + code + "?replacement=WORKER").andExpect(status().isNoContent());

        api.get(owner, org + "/roles/" + code).andExpect(status().isNotFound());
        String workers = body(api.get(owner, org + "/members?role=WORKER"));
        for (UUID membership : held) {
            assertTrue(workers.contains(membership.toString()));
        }
        api.get(owner, org + "/invitations").andExpect(jsonPath("$[0].role.code").value("WORKER"));
    }

    @Test
    void deleteNeedsReplacementEvenForFormerMembersOnly() throws Exception {
        String code = codeOf(create(owner, "Old crew", null, "[]"));
        UUID former = members.add(orgId, api.signUp().user(), code);
        jdbc.update("update organization_membership set status = 'LEFT' where id = ?", former);

        api.delete(owner, org + "/roles/" + code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_REPLACEMENT_REQUIRED"))
                .andExpect(jsonPath("$.memberCount").value(0))
                .andExpect(jsonPath("$.formerMemberCount").value(1))
                .andExpect(jsonPath("$.pendingInvitationCount").value(0));
        api.delete(owner, org + "/roles/" + code + "?replacement=WORKER").andExpect(status().isNoContent());
        assertEquals("WORKER", roleOf(former));
    }

    @Test
    void unusedCustomRoleIsDeletedWithoutAReplacement() throws Exception {
        String code = codeOf(create(admin, "Spare", null, "[]"));
        api.delete(admin, org + "/roles/" + code).andExpect(status().isNoContent());
        api.get(admin, org + "/roles/" + code).andExpect(status().isNotFound());
        api.get(admin, org + "/roles").andExpect(jsonPath("$[*].code").value(not(hasItem(code))));
    }

    @Test
    void builtInRolesCannotBeDeletedAndTheOwnerRoleIsLocked() throws Exception {
        api.delete(owner, org + "/roles/WORKER?replacement=MEMBER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_BUILT_IN_UNDELETABLE"));
        api.delete(owner, org + "/roles/OWNER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.delete(owner, org + "/roles/NOPE").andExpect(status().isNotFound());
    }

    @Test
    void replacementMustBeAnotherRoleTheCallerMayAssign() throws Exception {
        String code = codeOf(create(admin, "Yard crew", null, "[]"));
        members.add(orgId, api.signUp().user(), code);
        for (String replacement : List.of(code, "OWNER", "NOPE")) {
            api.delete(admin, org + "/roles/" + code + "?replacement=" + replacement)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ROLE_REPLACEMENT_INVALID"));
        }
        api.delete(admin, org + "/roles/" + code + "?replacement=FINANCE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.get(admin, org + "/roles/" + code).andExpect(jsonPath("$.memberCount").value(1));
    }

    @Test
    void createRenameEditAndDeleteAreAudited() throws Exception {
        String created = body(create(admin, "Yard crew", null, "[]").andExpect(status().isCreated()));
        String code = JsonPath.read(created, "$.code");
        UUID roleId = UUID.fromString(JsonPath.read(created, "$.id"));
        UUID moved = members.add(orgId, api.signUp().user(), code);
        long renamed = versionOf(api.put(admin, org + "/roles/" + code, text("Site crew", null, "[]", version(code))));
        api.put(admin, org + "/roles/" + code, text("Site crew", null, WORKER_WITH_PRICES, renamed))
                .andExpect(status().isOk());
        api.delete(admin, org + "/roles/" + code + "?replacement=WORKER").andExpect(status().isNoContent());

        UUID actor = memberships.findByOrganizationIdAndUserAccountId(orgId, admin.user().getId()).orElseThrow().getId();
        List<AuditService.AuditEventData> events = audit.listForEntity("ORGANIZATION_ROLE", roleId);
        assertEquals(List.of("DELETE", "UPDATE", "UPDATE", "CREATE"),
                events.stream().map(AuditService.AuditEventData::action).toList());
        events.forEach(e -> assertEquals(actor, e.actorMembershipId()));
        AuditService.AuditEventData create = events.get(3);
        assertNull(create.beforeState());
        assertTrue(create.afterState().contains("\"name\":\"Yard crew\""));
        AuditService.AuditEventData rename = events.get(2);
        assertTrue(rename.beforeState().contains("\"name\":\"Yard crew\""));
        assertTrue(rename.afterState().contains("\"name\":\"Site crew\""));
        assertTrue(events.get(1).afterState().contains("\"VIEW_PRICES\":\"ORGANIZATION\""));
        AuditService.AuditEventData delete = events.get(0);
        assertTrue(delete.beforeState().contains("\"code\":\"" + code + "\""));
        assertNull(delete.afterState());

        List<AuditService.AuditEventData> roleChanges = audit.listForEntity("ORGANIZATION_MEMBERSHIP", moved);
        assertEquals(1, roleChanges.size());
        assertEquals("ROLE_CHANGE", roleChanges.get(0).action());
        assertTrue(roleChanges.get(0).beforeState().contains(code));
        assertTrue(roleChanges.get(0).afterState().contains("WORKER"));
        assertEquals(actor, roleChanges.get(0).actorMembershipId());
    }

    /** Sends {@code count} role creates at once; returns "201" or the error code of each. */
    private List<String> inParallel(int count, IntFunction<String> body) throws Exception {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            calls.add(() -> {
                String response = body(api.post(owner, org + "/roles", body.apply(n)));
                return response.contains("\"code\":\"CUSTOM_") ? "201" : JsonPath.read(response, "$.code");
            });
        }
        return atOnce(calls);
    }

    /** Runs the calls on their own threads, released together; returns their results in order. */
    private static <T> List<T> atOnce(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> results = new ArrayList<>();
            for (Callable<T> call : calls) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> result : results) {
                values.add(result.get());
            }
            return values;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Runs {@code write} in a transaction on its own thread and keeps it open (with the row locks it took) while
     * {@code call} runs on another thread; commits it after {@code call} returns or has waited {@link #HOLD} ms.
     * Returns {@code call}'s result.
     */
    private <T> T whileHeld(Runnable write, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> held = pool.submit(() -> new TransactionTemplate(txm).executeWithoutResult(status -> {
                write.run();
                written.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            written.await();
            Future<T> result = pool.submit(call);
            try {
                result.get(HOLD, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // still waiting on the held write's locks
            }
            release.countDown();
            held.get();
            return result.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** What the Owner's PUT adding budgets does to the role's rows: lock it, add the grants, raise the version. */
    private void raiseToBudgets(String code) {
        UUID roleId = jdbc.queryForObject("select id from organization_role where organization_id = ? and code = ? "
                + "for update", UUID.class, orgId, code);
        for (String action : List.of("VIEW_BUDGET", "MANAGE_BUDGET")) {
            jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, ?, 'ORGANIZATION')",
                    UUID.randomUUID(), roleId, action);
        }
        jdbc.update("update organization_role set version = version + 1 where id = ?", roleId);
    }

    /** "200"/"204" on success, else the problem's code. */
    private static String outcomeOf(ResultActions result) throws Exception {
        int status = result.andReturn().getResponse().getStatus();
        return status < 300 ? String.valueOf(status) : JsonPath.read(body(result), "$.code");
    }

    private String roleOf(UUID membership) {
        return jdbc.queryForObject("select r.code from organization_membership m "
                + "join organization_role r on r.id = m.role_id where m.id = ?", String.class, membership);
    }

    private ResultActions create(Session session, String name, String description, String permissions)
            throws Exception {
        return api.post(session, org + "/roles", "{\"name\":" + json(name) + ",\"description\":" + json(description)
                + ",\"permissions\":" + permissions + "}");
    }

    private static String text(String name, String description, String permissions, long version) {
        return "{\"name\":" + json(name) + ",\"description\":" + json(description) + ",\"permissions\":"
                + permissions + ",\"version\":" + version + "}";
    }

    private long version(String code) throws Exception {
        return versionOf(api.get(owner, org + "/roles/" + code));
    }

    private static long versionOf(ResultActions result) throws Exception {
        Number version = JsonPath.read(body(result), "$.version");
        return version.longValue();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String codeOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.code");
    }

    private static String json(String text) {
        return text == null ? "null" : "\"" + text.replace("\"", "\\\"") + "\"";
    }
}

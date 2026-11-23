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

import java.util.List;
import java.util.UUID;

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
        assertEquals("WORKER", jdbc.queryForObject("select r.code from organization_membership m "
                + "join organization_role r on r.id = m.role_id where m.id = ?", String.class, former));
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

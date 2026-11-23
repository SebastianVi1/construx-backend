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
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage.PresignedUpload;
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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Editing a role's permissions and restoring a Built-in role's defaults (contract roles §5.4, §5.5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleEditFlowTest {
    /** The Member role's file defaults without MANAGE_PLAN. */
    private static final String MEMBER_WITHOUT_EDIT_PLANS = """
            [{"action":"VIEW_PROJECT","scope":"ORGANIZATION"},{"action":"VIEW_GOAL","scope":"ORGANIZATION"},
             {"action":"MANAGE_GOAL","scope":"ORGANIZATION"},{"action":"VIEW_ACTIVITY","scope":"ORGANIZATION"},
             {"action":"MANAGE_ACTIVITY","scope":"ORGANIZATION"},{"action":"ASSIGN_ACTIVITY","scope":"ORGANIZATION"},
             {"action":"VIEW_PRICES","scope":"ORGANIZATION"},{"action":"VIEW_PLAN","scope":"PROJECT"}]""";

    private static final String WORKER_DEFAULTS = """
            [{"action":"VIEW_PROJECT","scope":"PROJECT"},{"action":"VIEW_GOAL","scope":"PROJECT"},
             {"action":"VIEW_ACTIVITY","scope":"PROJECT"},{"action":"MANAGE_ACTIVITY","scope":"OWN"},
             {"action":"VIEW_PLAN","scope":"PROJECT"}]""";
    /** The Worker role's file defaults plus VIEW_PRICES. */
    private static final String WORKER_WITH_PRICES = """
            [{"action":"VIEW_PROJECT","scope":"PROJECT"},{"action":"VIEW_GOAL","scope":"PROJECT"},
             {"action":"VIEW_ACTIVITY","scope":"PROJECT"},{"action":"MANAGE_ACTIVITY","scope":"OWN"},
             {"action":"VIEW_PLAN","scope":"PROJECT"},{"action":"VIEW_PRICES","scope":"ORGANIZATION"}]""";

    @MockitoBean InvitationMailer mailer;
    @MockitoBean ObjectStorage storage;

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
    private UUID orgId;
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
        members.add(orgId, worker.user(), "WORKER");
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any(Duration.class)))
                .thenAnswer(call -> new PresignedUpload(URI.create("https://bucket.test/" + call.getArgument(0)),
                        Map.of("Content-Type", call.getArgument(1)), Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void ownerRemovesEditPlansFromMemberAndTheMemberLosesItOnTheNextRequest() throws Exception {
        Session member = api.signUp();
        UUID membership = members.add(orgId, member.user(), "MEMBER");
        String site = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}"));
        members.grantSite(UUID.fromString(site), membership);
        String upload = org + "/projects/" + site + "/plans/uploads";
        String plan = "{\"fileName\":\"plan.png\",\"contentType\":\"image/png\",\"sizeBytes\":10}";
        api.post(member, upload, plan).andExpect(status().isCreated());

        Session otherOwner = api.signUp();
        String other = "/api/organizations/" + api.createOrganization(otherOwner, "Other " + UUID.randomUUID());

        long version = version(owner, "MEMBER");
        api.put(owner, org + "/roles/MEMBER", edit(MEMBER_WITHOUT_EDIT_PLANS, version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("MEMBER"))
                .andExpect(jsonPath("$.customized").value(true))
                .andExpect(jsonPath("$.permissions[*].action").value(not(hasItem("MANAGE_PLAN"))))
                .andExpect(jsonPath("$.defaults[*].action").value(hasItem("MANAGE_PLAN")));

        api.post(member, upload, plan).andExpect(status().isForbidden());
        api.get(otherOwner, other + "/roles/MEMBER")
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.permissions[*].action").value(hasItem("MANAGE_PLAN")));
    }

    @Test
    void secondSaveWithTheSameVersionGetsRoleChangedAndEveryWriteRaisesTheVersionByOne() throws Exception {
        long read = version(owner, "WORKER");
        long first = versionOf(api.put(owner, org + "/roles/WORKER", edit(WORKER_WITH_PRICES, read)));
        assertEquals(read + 1, first, "one write raises the version by exactly one");
        api.put(admin, org + "/roles/WORKER", edit(WORKER_WITH_PRICES, read))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CHANGED"));
        api.post(admin, org + "/roles/WORKER/restore-defaults", "{\"version\":" + read + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CHANGED"));

        // Already customized: only one permission row's scope changes, the role row itself does not.
        long second = versionOf(api.put(owner, org + "/roles/WORKER",
                edit(WORKER_WITH_PRICES.replace("\"MANAGE_ACTIVITY\",\"scope\":\"OWN\"",
                        "\"MANAGE_ACTIVITY\",\"scope\":\"PROJECT\""), first)));
        assertEquals(first + 1, second);
        api.get(owner, org + "/roles/WORKER").andExpect(jsonPath("$.version").value(second));
        api.put(owner, org + "/roles/WORKER", edit(WORKER_WITH_PRICES, first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CHANGED"));
    }

    @Test
    void restoreBringsTheDefaultsBackAndClearsCustomized() throws Exception {
        long edited = versionOf(api.put(owner, org + "/roles/MEMBER",
                edit(MEMBER_WITHOUT_EDIT_PLANS, version(owner, "MEMBER"))));
        long restored = versionOf(api.post(owner, org + "/roles/MEMBER/restore-defaults",
                "{\"version\":" + edited + "}")
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.permissions[*].action").value(hasItem("MANAGE_PLAN"))));
        assertEquals(edited + 1, restored);
        api.get(owner, org + "/roles").andExpect(jsonPath("$[?(@.code == 'MEMBER')].customized").value(hasItem(false)));
    }

    @Test
    void putOfExactlyTheDefaultsClearsCustomized() throws Exception {
        long edited = versionOf(api.put(owner, org + "/roles/WORKER", edit(WORKER_WITH_PRICES, version(owner, "WORKER")))
                .andExpect(jsonPath("$.customized").value(true)));
        api.put(owner, org + "/roles/WORKER", edit(WORKER_DEFAULTS, edited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customized").value(false));
    }

    @Test
    void emptySetIsValid() throws Exception {
        api.put(owner, org + "/roles/WORKER", edit("[]", version(owner, "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0))
                .andExpect(jsonPath("$.customized").value(true));
    }

    @Test
    void setsBreakingTheRulesAreRejected() throws Exception {
        long version = version(owner, "WORKER");
        api.put(owner, org + "/roles/WORKER", edit("""
                [{"action":"VIEW_PROJECT","scope":"PROJECT"},{"action":"MANAGE_PLAN","scope":"PROJECT"}]""", version))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_DEPENDENCY_MISSING"))
                .andExpect(jsonPath("$.action").value("MANAGE_PLAN"))
                .andExpect(jsonPath("$.requires").value("VIEW_PLAN"));
        expectScopeInvalid("[{\"action\":\"VIEW_PRICES\",\"scope\":\"PROJECT\"}]", version);
        expectScopeInvalid("[{\"action\":\"FLY\",\"scope\":\"PROJECT\"}]", version);
        expectScopeInvalid("[{\"action\":\"VIEW_PROJECT\",\"scope\":\"EVERYWHERE\"}]", version);
        expectScopeInvalid("[{\"action\":\"VIEW_PROJECT\"}]", version);
        expectScopeInvalid("""
                [{"action":"VIEW_PROJECT","scope":"PROJECT"},{"action":"VIEW_PROJECT","scope":"ORGANIZATION"}]""",
                version);
        api.get(owner, org + "/roles/WORKER")
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.customized").value(false));
    }

    @Test
    void administratorCannotEditARoleAboveThemOrTheirOwnButTheOwnerCan() throws Exception {
        api.put(admin, org + "/roles/FINANCE", edit("[]", version(admin, "FINANCE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.post(admin, org + "/roles/FINANCE/restore-defaults", "{\"version\":" + version(admin, "FINANCE") + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.put(admin, org + "/roles/WORKER", edit(WORKER_WITH_PRICES.replace("[",
                        "[{\"action\":\"MANAGE_BUDGET\",\"scope\":\"ORGANIZATION\"},"
                                + "{\"action\":\"VIEW_BUDGET\",\"scope\":\"ORGANIZATION\"},"),
                version(admin, "WORKER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.put(admin, org + "/roles/ADMINISTRATOR", edit("[]", version(admin, "ADMINISTRATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_OWN"));
        api.post(admin, org + "/roles/ADMINISTRATOR/restore-defaults",
                        "{\"version\":" + version(admin, "ADMINISTRATOR") + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_OWN"));
        api.put(admin, org + "/roles/WORKER", edit(WORKER_WITH_PRICES, version(admin, "WORKER")))
                .andExpect(status().isOk());

        api.put(owner, org + "/roles/FINANCE", edit("[]", version(owner, "FINANCE"))).andExpect(status().isOk());
        api.put(owner, org + "/roles/ADMINISTRATOR", edit("[]", version(owner, "ADMINISTRATOR")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerRoleIsLocked() throws Exception {
        api.put(owner, org + "/roles/OWNER", edit("[]", version(owner, "OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.post(owner, org + "/roles/OWNER/restore-defaults", "{\"version\":" + version(owner, "OWNER") + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.get(owner, org + "/roles/OWNER").andExpect(jsonPath("$.permissions.length()").value(16));
    }

    @Test
    void builtInRejectsNameOnPut() throws Exception {
        long version = version(owner, "WORKER");
        api.put(owner, org + "/roles/WORKER",
                        "{\"name\":\"Worker\",\"permissions\":" + WORKER_DEFAULTS + ",\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_FIXED"));
        api.put(owner, org + "/roles/WORKER",
                        "{\"description\":\"x\",\"permissions\":" + WORKER_DEFAULTS + ",\"version\":" + version + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_FIXED"));
    }

    @Test
    void customRoleTakesNewPermissionsButCannotBeRestored() throws Exception {
        UUID role = UUID.randomUUID();
        jdbc.update("insert into organization_role (id, organization_id, code, name, description, system_role, seeded) "
                + "values (?, ?, 'CUSTOM_EEEEEEEEEE', 'Yard crew', null, false, false)", role, orgId);
        long version = version(owner, "CUSTOM_EEEEEEEEEE");
        long edited = versionOf(api.put(owner, org + "/roles/CUSTOM_EEEEEEEEEE",
                        "{\"name\":\"Yard crew\",\"description\":null,\"permissions\":"
                                + "[{\"action\":\"VIEW_PRICES\",\"scope\":\"ORGANIZATION\"}],\"version\":" + version + "}")
                .andExpect(jsonPath("$.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.permissions[0].action").value("VIEW_PRICES")));
        api.post(owner, org + "/roles/CUSTOM_EEEEEEEEEE/restore-defaults", "{\"version\":" + edited + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_BUILT_IN"));
    }

    @Test
    void writesNeedManageRolesAndAVersion() throws Exception {
        long version = version(owner, "WORKER");
        api.put(worker, org + "/roles/WORKER", edit("[]", version))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        api.post(worker, org + "/roles/MEMBER/restore-defaults", "{\"version\":0}")
                .andExpect(status().isForbidden());
        api.put(api.signUp(), org + "/roles/WORKER", edit("[]", version)).andExpect(status().isNotFound());
        api.put(owner, org + "/roles/NOPE", edit("[]", version)).andExpect(status().isNotFound());
        api.put(owner, org + "/roles/WORKER", "{\"permissions\":[]}").andExpect(status().isBadRequest());
        api.post(owner, org + "/roles/WORKER/restore-defaults", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void editsAndRestoresAreAuditedWithBeforeAndAfter() throws Exception {
        String roleId = JsonPath.read(body(api.get(owner, org + "/roles/MEMBER")), "$.id");
        long edited = versionOf(api.put(owner, org + "/roles/MEMBER",
                edit(MEMBER_WITHOUT_EDIT_PLANS, version(owner, "MEMBER"))));
        api.post(owner, org + "/roles/MEMBER/restore-defaults", "{\"version\":" + edited + "}")
                .andExpect(status().isOk());

        UUID actor = members.ownerMembership(orgId, owner.user());
        List<AuditService.AuditEventData> events = audit.listForEntity("ORGANIZATION_ROLE", UUID.fromString(roleId))
                .stream().filter(e -> e.actorMembershipId() != null).toList();
        assertEquals(List.of("RESTORE", "UPDATE"), events.stream().map(AuditService.AuditEventData::action).toList());
        events.forEach(e -> assertEquals(actor, e.actorMembershipId()));
        AuditService.AuditEventData update = events.get(1);
        assertTrue(update.beforeState().contains("\"MANAGE_PLAN\":\"PROJECT\""));
        assertFalse(update.afterState().contains("MANAGE_PLAN\""));
        assertTrue(update.afterState().contains("\"code\":\"MEMBER\""));
        AuditService.AuditEventData restore = events.get(0);
        assertFalse(restore.beforeState().contains("MANAGE_PLAN\""));
        assertTrue(restore.afterState().contains("\"MANAGE_PLAN\":\"PROJECT\""));
    }

    private void expectScopeInvalid(String permissions, long version) throws Exception {
        api.put(owner, org + "/roles/WORKER", edit(permissions, version))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_SCOPE_INVALID"));
    }

    private static long versionOf(ResultActions result) throws Exception {
        Number version = JsonPath.read(body(result), "$.version");
        return version.longValue();
    }

    private long version(Session session, String code) throws Exception {
        Number version = JsonPath.read(body(api.get(session, org + "/roles/" + code)), "$.version");
        return version.longValue();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String edit(String permissions, long version) {
        return "{\"permissions\":" + permissions + ",\"version\":" + version + "}";
    }
}

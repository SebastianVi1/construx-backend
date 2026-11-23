package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.service.InvitationMailer;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roles list and role detail (contract roles §5.1, §5.2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleFlowTest {
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
    }

    @Test
    void listIsOrderedOwnerBuiltInsThenCustomWithFlagsAndCounts() throws Exception {
        customRole("CUSTOM_ZZZZZZZZZZ", "zeta crew", null);
        customRole("CUSTOM_AAAAAAAAAA", "Yard crew", "Keeps the yard tidy");
        UUID suspended = members.add(orgId, api.signUp().user(), "WORKER");
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", suspended);
        UUID left = members.add(orgId, api.signUp().user(), "WORKER");
        jdbc.update("update organization_membership set status = 'LEFT' where id = ?", left);
        api.post(owner, org + "/invitations", "{\"email\":\"new@acme.test\",\"roleCode\":\"WORKER\"}")
                .andExpect(status().isCreated());

        api.get(admin, org + "/roles").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(contains("OWNER", "ADMINISTRATOR", "MEMBER", "WORKER",
                        "FINANCE", "RESIDENT", "SITE_MANAGER", "SUPERINTENDENT", "CUSTOM_AAAAAAAAAA",
                        "CUSTOM_ZZZZZZZZZZ")))
                .andExpect(jsonPath("$[*].kind").value(contains("OWNER", "BUILT_IN", "BUILT_IN", "BUILT_IN",
                        "BUILT_IN", "BUILT_IN", "BUILT_IN", "BUILT_IN", "CUSTOM", "CUSTOM")))
                .andExpect(jsonPath("$[3].id").isNotEmpty())
                .andExpect(jsonPath("$[3].name").value("Trabajador"))
                .andExpect(jsonPath("$[3].names.en").value("Worker"))
                .andExpect(jsonPath("$[3].description.en")
                        .value("Field staff: sees their sites and updates their own tasks."))
                .andExpect(jsonPath("$[3].description.es")
                        .value("Personal de campo: ve sus obras y actualiza sus propias tareas."))
                .andExpect(jsonPath("$[3].system").value(true))
                .andExpect(jsonPath("$[3].customized").value(false))
                .andExpect(jsonPath("$[3].memberCount").value(2))
                .andExpect(jsonPath("$[3].pendingInvitationCount").value(1))
                .andExpect(jsonPath("$[3].version").isNumber())
                .andExpect(jsonPath("$[0].memberCount").value(1))
                .andExpect(jsonPath("$[0].description.en").value(
                        "Created the company. Can do everything, including closing the company."))
                .andExpect(jsonPath("$[1].memberCount").value(1))
                .andExpect(jsonPath("$[4].memberCount").value(0))
                .andExpect(jsonPath("$[4].pendingInvitationCount").value(0))
                .andExpect(jsonPath("$[8].system").value(false))
                .andExpect(jsonPath("$[8].names.en").value("Yard crew"))
                .andExpect(jsonPath("$[8].names.es").value("Yard crew"))
                .andExpect(jsonPath("$[8].description.en").value("Keeps the yard tidy"))
                .andExpect(jsonPath("$[8].description.es").value("Keeps the yard tidy"))
                .andExpect(jsonPath("$[9].description.en").value(nullValue()))
                .andExpect(jsonPath("$[9].description.es").value(nullValue()));
    }

    @Test
    void administratorSeesTheFlagsOfTheFitsWithinRule() throws Exception {
        api.get(admin, org + "/roles")
                .andExpect(flag("FINANCE", "editable", false))
                .andExpect(flag("FINANCE", "assignable", false))
                .andExpect(flag("WORKER", "editable", true))
                .andExpect(flag("WORKER", "assignable", true))
                .andExpect(flag("ADMINISTRATOR", "editable", false))
                .andExpect(flag("ADMINISTRATOR", "assignable", true))
                .andExpect(flag("OWNER", "editable", false))
                .andExpect(flag("OWNER", "assignable", false));
        api.get(owner, org + "/roles")
                .andExpect(flag("FINANCE", "editable", true))
                .andExpect(flag("ADMINISTRATOR", "editable", true))
                .andExpect(flag("OWNER", "editable", false));
    }

    @Test
    void roleDetailAddsPermissionsInCatalogOrderAndBuiltInDefaults() throws Exception {
        api.get(admin, org + "/roles/WORKER").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WORKER"))
                .andExpect(jsonPath("$.kind").value("BUILT_IN"))
                .andExpect(jsonPath("$.names.es").value("Trabajador"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.assignable").value(true))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.permissions[*].action").value(contains(
                        "VIEW_PROJECT", "VIEW_GOAL", "VIEW_ACTIVITY", "MANAGE_ACTIVITY", "VIEW_PLAN")))
                .andExpect(jsonPath("$.permissions[*].scope").value(contains(
                        "PROJECT", "PROJECT", "PROJECT", "OWN", "PROJECT")))
                .andExpect(jsonPath("$.defaults[*].action").value(contains(
                        "VIEW_PROJECT", "VIEW_GOAL", "VIEW_ACTIVITY", "MANAGE_ACTIVITY", "VIEW_PLAN")));
        api.get(owner, org + "/roles/OWNER").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("OWNER"))
                .andExpect(jsonPath("$.permissions.length()").value(16))
                .andExpect(jsonPath("$.permissions[0].action").value("MANAGE_ORGANIZATION"))
                .andExpect(jsonPath("$.defaults").value(nullValue()));
    }

    @Test
    void customRoleDetailHasNoDefaults() throws Exception {
        UUID role = customRole("CUSTOM_BBBBBBBBBB", "Yard crew", null);
        jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, 'VIEW_PRICES', "
                + "'ORGANIZATION')", UUID.randomUUID(), role);
        api.get(admin, org + "/roles/CUSTOM_BBBBBBBBBB").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.permissions[0].action").value("VIEW_PRICES"))
                .andExpect(jsonPath("$.permissions[0].scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.defaults").value(nullValue()));
    }

    @Test
    void unknownRoleIsNotFound() throws Exception {
        api.get(admin, org + "/roles/NOPE").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void workerGets403OnRoles() throws Exception {
        api.get(worker, org + "/roles").andExpect(status().isForbidden());
        api.get(worker, org + "/roles/WORKER").andExpect(status().isForbidden());
        api.get(api.signUp(), org + "/roles").andExpect(status().isNotFound());
    }

    @Test
    void manageRolesAloneIsEnoughToRead() throws Exception {
        UUID role = customRole("CUSTOM_CCCCCCCCCC", "Role keepers", null);
        jdbc.update("insert into role_permission (id, role_id, action, scope) values (?, ?, 'MANAGE_ROLES', "
                + "'ORGANIZATION')", UUID.randomUUID(), role);
        Session keeper = api.signUp();
        members.add(orgId, keeper.user(), "CUSTOM_CCCCCCCCCC");
        api.get(keeper, org + "/roles").andExpect(status().isOk())
                .andExpect(flag("CUSTOM_CCCCCCCCCC", "editable", false))
                .andExpect(flag("WORKER", "editable", false))
                .andExpect(flag("WORKER", "assignable", false));
        api.get(keeper, org + "/roles/WORKER").andExpect(status().isOk());
        api.get(keeper, org + "/members").andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultMatcher flag(String code, String field, boolean value) {
        return jsonPath("$[?(@.code == '" + code + "')]." + field).value(hasItem(value));
    }

    private UUID customRole(String code, String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization_role (id, organization_id, code, name, description, system_role, seeded) "
                + "values (?, ?, ?, ?, ?, false, false)", id, orgId, code, name, description);
        return id;
    }
}

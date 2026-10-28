package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationMembersFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Members members;
    private Session owner, worker;
    private UUID orgId, workerMembership;
    private String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        worker = api.signUp();
        profiles.save(new UserProfile(worker.user(), "Ana Perez", "+5215512345678"));
        workerMembership = members.add(orgId, worker.user(), "WORKER");
    }

    @Test
    void organizationResponsesCarryTheCallersRole() throws Exception {
        api.post(owner, "/api/organizations", "{\"name\":\"Second\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.myRole.code").value("OWNER"))
                .andExpect(jsonPath("$.myRole.name").value("Propietario"));
        api.get(owner, org).andExpect(jsonPath("$.myRole.code").value("OWNER"));
        api.get(worker, "/api/organizations").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].myRole.code").value("WORKER"));
        api.patch(owner, org, "{\"name\":\"Renamed\"}").andExpect(jsonPath("$.myRole.code").value("OWNER"));
    }

    @Test
    void meReturnsMembershipRoleAndPermissions() throws Exception {
        api.get(worker, org + "/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.permissions.length()").value(4))
                .andExpect(jsonPath("$.permissions[?(@.action == 'MANAGE_ACTIVITY')].scope").value(hasItem("OWN")))
                .andExpect(jsonPath("$.permissions[?(@.action == 'VIEW_PROJECT')].scope").value(hasItem("PROJECT")));
        api.get(owner, org + "/me").andExpect(jsonPath("$.permissions.length()").value(14));
        Session stranger = api.signUp();
        api.get(stranger, org + "/me").andExpect(status().isNotFound());
    }

    @Test
    void rolesListsTheSeededRolesForInviters() throws Exception {
        api.get(owner, org + "/roles").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].code").value(containsInAnyOrder(
                        "OWNER", "ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE")))
                .andExpect(jsonPath("$[?(@.code == 'WORKER')].system").value(hasItem(true)))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
        api.get(worker, org + "/roles").andExpect(status().isForbidden());
        api.get(api.signUp(), org + "/roles").andExpect(status().isNotFound());
    }

    @Test
    void membersListsPeopleWithEmailNameRoleAndFiltersByStatus() throws Exception {
        api.get(owner, org + "/members").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].email")
                        .value(hasItem(worker.user().getEmail())))
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].fullName")
                        .value(hasItem("Ana Perez")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].role.code")
                        .value(hasItem("WORKER")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].userId")
                        .value(hasItem(worker.user().getId().toString())));
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", workerMembership);
        api.get(owner, org + "/members").andExpect(jsonPath("$.length()").value(1));
        api.get(owner, org + "/members?status=SUSPENDED").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUSPENDED"));
    }

    @Test
    void membersIsForbiddenWithoutManageMembersAndHiddenFromOutsiders() throws Exception {
        api.get(worker, org + "/members").andExpect(status().isForbidden());
        Session member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        api.get(member, org + "/members").andExpect(status().isForbidden());
        api.get(api.signUp(), org + "/members").andExpect(status().isNotFound());
    }

    @Test
    void ownerCanUpdateMemberRoleButRegularMemberCannot() throws Exception {
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"ADMINISTRATOR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.role.code").value("ADMINISTRATOR"));
        api.patch(worker, org + "/members/" + workerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isForbidden());
        api.patch(owner, org + "/members/" + UUID.randomUUID(), "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void roleChangeCannotGrantOrReplaceOwnerNorChangeYourOwnRole() throws Exception {
        Session admin = api.signUp();
        UUID adminMembership = members.add(orgId, admin.user(), "ADMINISTRATOR");
        UUID ownerMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();

        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"OWNER\"}")
                .andExpect(status().isBadRequest());
        api.patch(admin, org + "/members/" + ownerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isConflict());
        api.patch(admin, org + "/members/" + adminMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isForbidden());
        api.patch(owner, org + "/members/" + ownerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isForbidden());
        api.get(owner, org + "/members")
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].role.code").value(hasItem("WORKER")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + ownerMembership + "')].role.code").value(hasItem("OWNER")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + adminMembership + "')].role.code")
                        .value(hasItem("ADMINISTRATOR")));
    }

    @Test
    void roleChangeRejectsUnknownOrMissingRolesAndHidesOrganizationFromOutsiders() throws Exception {
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"NOPE\"}")
                .andExpect(status().isBadRequest());
        api.patch(owner, org + "/members/" + workerMembership, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.patch(api.signUp(), org + "/members/" + workerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isNotFound());
    }
}

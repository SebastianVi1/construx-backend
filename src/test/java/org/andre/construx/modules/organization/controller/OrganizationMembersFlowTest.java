package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import com.jayway.jsonpath.JsonPath;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationMembersFlowTest {
    @TestConfiguration
    static class Storage {
        @Bean @Primary ObjectStorage inMemoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

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
    @Autowired AuditService audit;

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
    void summariesCarryEnglishAndSpanishNames() throws Exception {
        api.get(worker, org + "/me")
                .andExpect(jsonPath("$.role.name").value("Trabajador"))
                .andExpect(jsonPath("$.role.names.en").value("Worker"))
                .andExpect(jsonPath("$.role.names.es").value("Trabajador"));
        api.get(worker, "/api/organizations")
                .andExpect(jsonPath("$[0].myRole.names.en").value("Worker"))
                .andExpect(jsonPath("$[0].myRole.names.es").value("Trabajador"));
        api.get(owner, org)
                .andExpect(jsonPath("$.myRole.names.en").value("Owner"))
                .andExpect(jsonPath("$.myRole.names.es").value("Propietario"));
        api.post(owner, "/api/organizations", "{\"name\":\"Second\"}")
                .andExpect(jsonPath("$.myRole.names.en").value("Owner"));
        api.get(owner, org + "/members")
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].role.names.en")
                        .value(hasItem("Worker")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].role.names.es")
                        .value(hasItem("Trabajador")));
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"SITE_MANAGER\"}")
                .andExpect(jsonPath("$.role.names.en").value("Site Manager"))
                .andExpect(jsonPath("$.role.names.es").value("Jefe de Obra"));
        api.get(owner, org + "/roles")
                .andExpect(jsonPath("$[?(@.code == 'FINANCE')].names.en").value(hasItem("Finance")))
                .andExpect(jsonPath("$[?(@.code == 'OWNER')].names.es").value(hasItem("Propietario")));
    }

    @Test
    void customRoleNamesAreTheStoredNameInBothLanguages() throws Exception {
        jdbc.update("insert into organization_role (id, organization_id, code, name, system_role, seeded) "
                + "values (?, ?, 'FOREMAN', 'Capataz', false, false)", UUID.randomUUID(), orgId);
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"FOREMAN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.name").value("Capataz"))
                .andExpect(jsonPath("$.role.names.en").value("Capataz"))
                .andExpect(jsonPath("$.role.names.es").value("Capataz"));
    }

    @Test
    void meReturnsMembershipRoleAndPermissions() throws Exception {
        api.get(worker, org + "/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.permissions.length()").value(5))
                .andExpect(jsonPath("$.permissions[?(@.action == 'MANAGE_ACTIVITY')].scope").value(hasItem("OWN")))
                .andExpect(jsonPath("$.permissions[?(@.action == 'VIEW_PROJECT')].scope").value(hasItem("PROJECT")));
        api.get(owner, org + "/me").andExpect(jsonPath("$.permissions.length()").value(16));
        Session stranger = api.signUp();
        api.get(stranger, org + "/me").andExpect(status().isNotFound());
    }

    @Test
    void rolesListsTheSeededRolesForInviters() throws Exception {
        api.get(owner, org + "/roles").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[*].code").value(containsInAnyOrder(
                        "OWNER", "ADMINISTRATOR", "MEMBER", "WORKER", "FINANCE",
                        "RESIDENT", "SITE_MANAGER", "SUPERINTENDENT")))
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
    void membersFilterByRole() throws Exception {
        UUID secondWorker = members.add(orgId, api.signUp().user(), "WORKER");
        UUID suspendedWorker = members.add(orgId, api.signUp().user(), "WORKER");
        members.add(orgId, api.signUp().user(), "MEMBER");
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", suspendedWorker);

        api.get(owner, org + "/members?role=WORKER").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].membershipId").value(containsInAnyOrder(
                        workerMembership.toString(), secondWorker.toString())))
                .andExpect(jsonPath("$[*].role.code").value(containsInAnyOrder("WORKER", "WORKER")));
        api.get(owner, org + "/members?status=SUSPENDED&role=WORKER")
                .andExpect(jsonPath("$[*].membershipId").value(containsInAnyOrder(suspendedWorker.toString())));
        api.get(owner, org + "/members?role=FINANCE").andExpect(jsonPath("$.length()").value(0));
        api.get(owner, org + "/members?role=NO_SUCH_ROLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        api.get(owner, org + "/members").andExpect(jsonPath("$.length()").value(4));
        api.get(worker, org + "/members?role=WORKER").andExpect(status().isForbidden());
    }

    @Test
    void membersAndMemberUpdatesCarryTheProfilePhoto() throws Exception {
        UserProfile profile = profiles.findById(worker.user().getId()).orElseThrow();
        profile.changeAvatarKey("users/" + worker.user().getId() + "/avatar/1");
        profiles.save(profile);
        String photo = "memory://download/users/" + worker.user().getId() + "/avatar/1";

        api.get(owner, org + "/members")
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].avatarUrl").value(hasItem(photo)))
                .andExpect(jsonPath("$[?(@.membershipId != '" + workerMembership + "')].avatarUrl")
                        .value(hasItem(nullValue())));
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(jsonPath("$.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.avatarUrl").value(photo));
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
    void ownRoleChangeIsRoleOwnAndOwnerRoleIsLocked() throws Exception {
        Session admin = api.signUp();
        UUID adminMembership = members.add(orgId, admin.user(), "ADMINISTRATOR");
        UUID ownerMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();

        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"OWNER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.patch(owner, org + "/members/" + workerMembership, "{\"roleCode\":\"OWNER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.patch(admin, org + "/members/" + ownerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_OWNER_LOCKED"));
        api.patch(admin, org + "/members/" + adminMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_OWN"));
        api.patch(owner, org + "/members/" + ownerMembership, "{\"roleCode\":\"MEMBER\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_OWN"));
        api.get(owner, org + "/members")
                .andExpect(jsonPath("$[?(@.membershipId == '" + workerMembership + "')].role.code").value(hasItem("WORKER")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + ownerMembership + "')].role.code").value(hasItem("OWNER")))
                .andExpect(jsonPath("$[?(@.membershipId == '" + adminMembership + "')].role.code")
                        .value(hasItem("ADMINISTRATOR")));
    }

    @Test
    void administratorMovesWorkerToResidentButNotIntoFinance() throws Exception {
        Session admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");

        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"FINANCE\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"RESIDENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.role.code").value("RESIDENT"))
                .andExpect(jsonPath("$.role.names.en").value("Resident"));
        api.get(admin, org + "/members?role=RESIDENT")
                .andExpect(jsonPath("$[*].membershipId").value(containsInAnyOrder(workerMembership.toString())));
    }

    @Test
    void cannotReassignMemberWhoseCurrentRoleIsAboveCaller() throws Exception {
        Session admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");
        UUID finance = members.add(orgId, api.signUp().user(), "FINANCE");
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", finance);

        api.patch(admin, org + "/members/" + finance, "{\"roleCode\":\"WORKER\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_ABOVE_CALLER"));
        api.patch(owner, org + "/members/" + finance, "{\"roleCode\":\"WORKER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void assignmentsAreAuditedAsRoleChangesWithBeforeAndAfterRole() throws Exception {
        Session admin = api.signUp();
        UUID adminMembership = members.add(orgId, admin.user(), "ADMINISTRATOR");

        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"RESIDENT\"}")
                .andExpect(status().isOk());
        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"FINANCE\"}")
                .andExpect(status().isForbidden());
        api.patch(admin, org + "/members/" + workerMembership, "{\"roleCode\":\"RESIDENT\"}")
                .andExpect(status().isOk());

        List<AuditService.AuditEventData> events = audit.listForEntity("ORGANIZATION_MEMBERSHIP", workerMembership);
        assertEquals(1, events.size());
        AuditService.AuditEventData event = events.getFirst();
        assertEquals("ROLE_CHANGE", event.action());
        assertEquals(adminMembership, event.actorMembershipId());
        assertEquals("WORKER", JsonPath.read(event.beforeState(), "$.roleCode"));
        assertEquals("RESIDENT", JsonPath.read(event.afterState(), "$.roleCode"));
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

    @Test
    void ownerCanSuspendAndReactivateButNotSelfOrOwner() throws Exception {
        UUID ownerMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();

        api.patch(owner, org + "/members/" + workerMembership + "/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        api.get(owner, org + "/members").andExpect(jsonPath("$.length()").value(1));

        api.patch(owner, org + "/members/" + workerMembership + "/status", "{\"status\":\"ACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Trying to change your own status is forbidden
        api.patch(owner, org + "/members/" + ownerMembership + "/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isForbidden());

        // An admin trying to suspend the owner hits the domain rule → 409
        Session admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");
        api.patch(admin, org + "/members/" + ownerMembership + "/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isConflict());

        api.patch(owner, org + "/members/" + UUID.randomUUID() + "/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isNotFound());

        api.patch(worker, org + "/members/" + ownerMembership + "/status", "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanRemoveMemberButNotSelf() throws Exception {
        UUID ownerMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();

        api.delete(owner, org + "/members/" + ownerMembership).andExpect(status().isForbidden());

        api.delete(owner, org + "/members/" + workerMembership).andExpect(status().isNoContent());

        api.get(owner, org + "/members").andExpect(jsonPath("$.length()").value(1));

        api.delete(owner, org + "/members/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}

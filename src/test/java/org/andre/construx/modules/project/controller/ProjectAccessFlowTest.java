package org.andre.construx.modules.project.controller;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectAccessFlowTest {
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
    private Session owner;
    private UUID orgId;
    private String site;
    private String access;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        site = "/api/organizations/" + orgId + "/projects/" + Api.id(api.post(owner,
                "/api/organizations/" + orgId + "/projects", "{\"name\":\"Tower\"}"));
        access = site + "/access";
    }

    @Test
    void ownerAssignsAMemberToTheSiteAndTheListShowsNameAndRoleButNoEmail() throws Exception {
        Session ana = api.signUp();
        profiles.save(new UserProfile(ana.user(), "Ana Perez", "+5215512345678"));
        UUID membership = members.add(orgId, ana.user(), "WORKER");

        api.post(owner, access, "{\"membershipId\":\"" + membership + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessId").isNotEmpty())
                .andExpect(jsonPath("$.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.userId").value(ana.user().getId().toString()))
                .andExpect(jsonPath("$.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.email").doesNotExist());
        api.get(owner, access).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Ana Perez"));
    }

    @Test
    void memberWithoutProfileHasNullNameAndDuplicateAssignmentConflicts() throws Exception {
        Session bob = api.signUp();
        UUID membership = members.add(orgId, bob.user(), "MEMBER");
        api.post(owner, access, "{\"membershipId\":\"" + membership + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fullName").isEmpty());
        api.post(owner, access, "{\"membershipId\":\"" + membership + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    void membershipsFromOtherTenantsUnknownOrSuspendedAreRejected() throws Exception {
        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        UUID foreignMembership = members.ownerMembership(strangerOrg, stranger.user());
        api.post(owner, access, "{\"membershipId\":\"" + foreignMembership + "\"}").andExpect(status().isNotFound());
        api.post(owner, access, "{\"membershipId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        api.post(owner, access, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Session carl = api.signUp();
        UUID suspended = members.add(orgId, carl.user(), "WORKER");
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", suspended);
        api.post(owner, access, "{\"membershipId\":\"" + suspended + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void listShowsOnlyActiveMembers() throws Exception {
        Session dana = api.signUp();
        UUID membership = members.add(orgId, dana.user(), "WORKER");
        api.post(owner, access, "{\"membershipId\":\"" + membership + "\"}").andExpect(status().isCreated());
        jdbc.update("update organization_membership set status = 'SUSPENDED' where id = ?", membership);
        api.get(owner, access).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void removeAccessThenNotFoundAndBlockedWhileInAnyTaskTeam() throws Exception {
        Session eve = api.signUp();
        UUID membership = members.add(orgId, eve.user(), "WORKER");
        String accessId = com.jayway.jsonpath.JsonPath.read(api.post(owner, access,
                        "{\"membershipId\":\"" + membership + "\"}").andReturn().getResponse().getContentAsString(),
                "$.accessId");

        UUID projectId = UUID.fromString(site.substring(site.lastIndexOf('/') + 1));
        UUID goal = UUID.randomUUID();
        Timestamp due = Timestamp.from(Instant.now().plusSeconds(86_400));
        jdbc.update("insert into goal (id, project_id, name, start_at, due_at) values (?, ?, 'G', ?, ?)", goal, projectId, due, due);
        UUID task = UUID.randomUUID();
        jdbc.update("insert into activity (id, goal_id, name, start_at, due_at, status) values (?, ?, 'T', ?, ?, 'DONE')",
                task, goal, due, due);
        jdbc.update("insert into activity_assignee (id, activity_id, access_id, task_role) values (?, ?, ?, 'CREW')",
                UUID.randomUUID(), task, UUID.fromString(accessId));
        api.delete(owner, access + "/" + accessId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        jdbc.update("delete from activity_assignee where activity_id = ?", task);
        jdbc.update("delete from activity where id = ?", task);
        api.delete(owner, access + "/" + accessId).andExpect(status().isNoContent());
        api.get(owner, access).andExpect(jsonPath("$.length()").value(0));
        api.delete(owner, access + "/" + accessId).andExpect(status().isNotFound());
        assertEquals(1L, jdbc.queryForObject("select count(*) from organization_membership where id = ?",
                Long.class, membership));
    }

    @Test
    void workersAndMembersCanViewButNotManageAndOutsidersGetNotFound() throws Exception {
        Session worker = api.signUp();
        UUID workerMembership = members.add(orgId, worker.user(), "WORKER");
        Session member = api.signUp();
        UUID memberMembership = members.add(orgId, member.user(), "MEMBER");

        api.get(worker, access).andExpect(status().isForbidden());
        api.post(owner, access, "{\"membershipId\":\"" + workerMembership + "\"}").andExpect(status().isCreated());
        api.get(worker, access).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.post(worker, access, "{\"membershipId\":\"" + memberMembership + "\"}").andExpect(status().isForbidden());
        api.get(member, access).andExpect(status().isOk());
        api.post(member, access, "{\"membershipId\":\"" + memberMembership + "\"}").andExpect(status().isForbidden());

        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, access).andExpect(status().isNotFound());
        api.post(stranger, access, "{\"membershipId\":\"" + workerMembership + "\"}").andExpect(status().isNotFound());
    }
}

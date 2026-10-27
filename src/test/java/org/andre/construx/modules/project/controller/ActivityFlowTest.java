package org.andre.construx.modules.project.controller;

import com.jayway.jsonpath.JsonPath;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityFlowTest {
    private static final String GOAL_DUE = "2027-03-01T00:00:00Z";

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
    private Session owner, member, ana, bob;
    private UUID orgId, projectId, anaAccess, bobAccess;
    private String org, tasks;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        projectId = UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}")));
        String goalId = Api.id(api.post(owner, org + "/projects/" + projectId + "/goals",
                "{\"name\":\"Phase\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
        tasks = org + "/projects/" + projectId + "/goals/" + goalId + "/activities";

        member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        ana = api.signUp();
        profiles.save(new UserProfile(ana.user(), "Ana Perez", "+5215512345678"));
        anaAccess = members.grantSite(projectId, members.add(orgId, ana.user(), "WORKER"));
        bob = api.signUp();
        bobAccess = members.grantSite(projectId, members.add(orgId, bob.user(), "WORKER"));
    }

    private String create(Session as, String name, UUID assignee) throws Exception {
        return Api.id(api.post(as, tasks, body(name, "2027-02-01T18:00:00Z", assignee)).andExpect(status().isCreated()));
    }

    private static String body(String name, String dueAt, UUID assignee) {
        return "{\"name\":\"" + name + "\",\"dueAt\":\"" + dueAt + "\",\"assigneeAccessId\":\"" + assignee + "\"}";
    }

    @Test
    void memberCreatesATaskForAWorkerOnTheSite() throws Exception {
        api.post(member, tasks, body(" Pour slab level 1 ", "2027-02-01T18:00:00Z", anaAccess))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Pour slab level 1"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-01T18:00:00Z"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.assignee.accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignee.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.assignee.membershipId").isNotEmpty());
        api.get(owner, tasks).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidTasksAreRejected() throws Exception {
        api.post(member, tasks, "{\"name\":\"No assignee\",\"dueAt\":\"2027-02-01T18:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(member, tasks, body("Too late", "2027-03-02T00:00:00Z", anaAccess))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        String otherSite = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Other\"}"));
        UUID foreignAccess = members.grantSite(UUID.fromString(otherSite), members.ownerMembership(orgId, owner.user()));
        api.post(member, tasks, body("Wrong site", "2027-02-01T18:00:00Z", foreignAccess))
                .andExpect(status().isNotFound());
        assertEquals(0L, jdbc.queryForObject("select count(*) from activity", Long.class) - countOthers());
    }

    private long countOthers() {
        return jdbc.queryForObject("select count(*) from activity a join goal g on g.id = a.goal_id where g.project_id <> ?",
                Long.class, projectId);
    }

    @Test
    void workerStartsAndCompletesOnlyTheirOwnTasks() throws Exception {
        String mine = create(member, "Ana's", anaAccess);
        String theirs = create(member, "Bob's", bobAccess);

        api.get(ana, tasks).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        api.get(ana, tasks + "/" + theirs).andExpect(status().isOk());
        api.post(ana, tasks + "/" + mine + "/start", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        api.post(ana, tasks + "/" + mine + "/start", null).andExpect(status().isConflict());
        api.post(ana, tasks + "/" + mine + "/complete", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        api.post(ana, tasks + "/" + mine + "/complete", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        api.post(ana, tasks + "/" + theirs + "/start", null).andExpect(status().isForbidden());
        api.post(ana, tasks + "/" + theirs + "/complete", null).andExpect(status().isForbidden());
        api.post(ana, tasks, body("Self-made", "2027-02-01T18:00:00Z", anaAccess)).andExpect(status().isForbidden());
        api.patch(ana, tasks + "/" + mine, "{\"name\":\"Renamed\"}").andExpect(status().isForbidden());
        api.put(ana, tasks + "/" + theirs + "/assignee", "{\"assigneeAccessId\":\"" + anaAccess + "\"}")
                .andExpect(status().isForbidden());
        api.delete(ana, tasks + "/" + theirs).andExpect(status().isForbidden());
    }

    @Test
    void reassigningMovesTheRightToCompleteTheTask() throws Exception {
        String task = create(member, "Shared", anaAccess);
        api.put(member, tasks + "/" + task + "/assignee", "{\"assigneeAccessId\":\"" + bobAccess + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee.accessId").value(bobAccess.toString()));
        api.post(ana, tasks + "/" + task + "/complete", null).andExpect(status().isForbidden());
        api.post(bob, tasks + "/" + task + "/complete", null).andExpect(status().isOk());
    }

    @Test
    void patchRenamesAndReschedulesWithinTheGoalAndDeleteRemoves() throws Exception {
        String task = create(member, "Draft", anaAccess);
        api.patch(member, tasks + "/" + task, "{\"name\":\"Final\",\"dueAt\":\"2027-02-10T00:00:00Z\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Final"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-10T00:00:00Z"))
                .andExpect(jsonPath("$.status").value("TODO"));
        api.patch(member, tasks + "/" + task, "{\"dueAt\":\"2027-04-01T00:00:00Z\"}").andExpect(status().isBadRequest());
        api.delete(member, tasks + "/" + task).andExpect(status().isNoContent());
        api.get(member, tasks + "/" + task).andExpect(status().isNotFound());
    }

    @Test
    void listFiltersByStatusAndAssignee() throws Exception {
        String first = create(member, "A1", anaAccess);
        create(member, "A2", anaAccess);
        create(member, "B1", bobAccess);
        api.post(ana, tasks + "/" + first + "/complete", null).andExpect(status().isOk());
        api.get(owner, tasks + "?status=DONE").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("A1"));
        api.get(owner, tasks + "?assigneeAccessId=" + bobAccess).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("B1"));
        api.get(owner, tasks + "?status=TODO&assigneeAccessId=" + anaAccess).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("A2"));
    }

    @Test
    void mineListsTheCallersTasksAcrossSites() throws Exception {
        create(member, "Tower task", anaAccess);
        create(member, "Bob task", bobAccess);
        String otherSite = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Other\"}"));
        UUID anaMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, ana.user().getId()).orElseThrow().getId();
        UUID anaOther = members.grantSite(UUID.fromString(otherSite), anaMembership);
        String otherGoal = Api.id(api.post(owner, org + "/projects/" + otherSite + "/goals",
                "{\"name\":\"G\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
        String done = Api.id(api.post(owner, org + "/projects/" + otherSite + "/goals/" + otherGoal + "/activities",
                body("Other task", "2027-01-01T00:00:00Z", anaOther)));
        api.post(ana, org + "/projects/" + otherSite + "/goals/" + otherGoal + "/activities/" + done + "/complete", null)
                .andExpect(status().isOk());

        api.get(ana, org + "/activities/mine").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Other task"))
                .andExpect(jsonPath("$[0].projectName").value("Other"))
                .andExpect(jsonPath("$[0].goalName").value("G"))
                .andExpect(jsonPath("$[1].name").value("Tower task"))
                .andExpect(jsonPath("$[1].projectName").value("Tower"))
                .andExpect(jsonPath("$[1].goalName").value("Phase"));
        api.get(ana, org + "/activities/mine?status=TODO").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void financeCannotSeeTasksAndOutsidersGetNotFound() throws Exception {
        String task = create(member, "Secret", anaAccess);
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        api.get(finance, tasks).andExpect(status().isForbidden());
        api.get(finance, org + "/activities/mine").andExpect(status().isForbidden());

        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, tasks).andExpect(status().isNotFound());
        api.get(stranger, tasks + "/" + task).andExpect(status().isNotFound());
        api.post(stranger, tasks + "/" + task + "/complete", null).andExpect(status().isNotFound());
        api.get(stranger, "/api/organizations/" + strangerOrg + "/activities/mine")
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        String otherGoal = Api.id(api.post(owner, org + "/projects/" + projectId + "/goals",
                "{\"name\":\"Other phase\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
        api.get(owner, org + "/projects/" + projectId + "/goals/" + otherGoal + "/activities/" + task)
                .andExpect(status().isNotFound());
    }

    @Test
    void workerWithoutSiteAccessCannotListTheSitesTasks() throws Exception {
        Session carl = api.signUp();
        members.add(orgId, carl.user(), "WORKER");
        api.get(carl, tasks).andExpect(status().isForbidden());
        String task = create(member, "Not yours", anaAccess);
        api.get(carl, tasks + "/" + task).andExpect(status().isForbidden());
        assertEquals(1, ((java.util.List<?>) JsonPath.read(api.get(ana, tasks).andReturn().getResponse()
                .getContentAsString(), "$")).size());
    }
}

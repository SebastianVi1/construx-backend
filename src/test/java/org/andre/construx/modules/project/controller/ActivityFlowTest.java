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
    private static final String GOAL_START = "2027-01-01T00:00:00Z";
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
                "{\"name\":\"Phase\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
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
        return body(name, GOAL_START, dueAt, assignee);
    }

    private static String body(String name, String startAt, String dueAt, UUID assignee) {
        return "{\"name\":\"" + name + "\",\"startAt\":\"" + startAt + "\",\"dueAt\":\"" + dueAt
                + "\",\"assignees\":" + team(assignee, "LEAD") + "}";
    }

    /** JSON team from alternating access ids and task roles. */
    private static String team(Object... accessAndRole) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < accessAndRole.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"accessId\":\"").append(accessAndRole[i])
                    .append("\",\"taskRole\":\"").append(accessAndRole[i + 1]).append("\"}");
        }
        return json.append("]").toString();
    }

    private String createWithTeam(String name, Object... accessAndRole) throws Exception {
        return Api.id(api.post(member, tasks, "{\"name\":\"" + name + "\",\"startAt\":\"" + GOAL_START
                + "\",\"dueAt\":\"2027-02-01T18:00:00Z\",\"assignees\":" + team(accessAndRole) + "}")
                .andExpect(status().isCreated()));
    }

    @Test
    void createRequiresAStartInsideThePhase() throws Exception {
        api.post(member, tasks, "{\"name\":\"No start\",\"dueAt\":\"2027-02-01T18:00:00Z\",\"assignees\":" + team(anaAccess, "LEAD") + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(member, tasks, body("Too early", "2026-12-31T23:59:59Z", "2027-02-01T18:00:00Z", anaAccess))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(member, tasks, body("Backwards", "2027-02-02T00:00:00Z", "2027-02-01T18:00:00Z", anaAccess))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(member, tasks, body("Pour", "2027-01-20T08:00:00Z", "2027-02-01T18:00:00Z", anaAccess))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startAt").value("2027-01-20T08:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-01T18:00:00Z"));
        api.get(owner, tasks).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].startAt").value("2027-01-20T08:00:00Z"));
    }

    @Test
    void patchMovesBothDatesAsAPair() throws Exception {
        String task = Api.id(api.post(member, tasks, body("Pour", "2027-01-02T00:00:00Z", "2027-01-03T00:00:00Z", anaAccess)));
        api.patch(member, tasks + "/" + task, "{\"startAt\":\"2027-02-20T00:00:00Z\",\"dueAt\":\"2027-02-25T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2027-02-20T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-25T00:00:00Z"));
        api.patch(member, tasks + "/" + task, "{\"startAt\":\"2027-02-21T00:00:00Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2027-02-21T00:00:00Z"));
        api.patch(member, tasks + "/" + task, "{\"startAt\":\"2026-12-01T00:00:00Z\",\"dueAt\":\"2027-01-05T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.patch(member, tasks + "/" + task, "{\"startAt\":\"2027-02-26T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, tasks + "/" + task).andExpect(jsonPath("$.startAt").value("2027-02-21T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-25T00:00:00Z"));
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
                .andExpect(jsonPath("$.assignees.length()").value(1))
                .andExpect(jsonPath("$.assignees[0].accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignees[0].fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.assignees[0].membershipId").isNotEmpty())
                .andExpect(jsonPath("$.assignees[0].taskRole").value("LEAD"));
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
        api.put(ana, tasks + "/" + theirs + "/assignees", "{\"assignees\":" + team(anaAccess, "LEAD") + "}")
                .andExpect(status().isForbidden());
        api.put(ana, tasks + "/" + mine + "/assignees", "{\"assignees\":" + team(anaAccess, "LEAD", bobAccess, "CREW") + "}")
                .andExpect(status().isForbidden());
        api.delete(ana, tasks + "/" + theirs).andExpect(status().isForbidden());
    }

    @Test
    void teamIsListedMostSeniorFirstWithOneLead() throws Exception {
        UUID carlaAccess = members.grantSite(projectId, members.add(orgId, api.signUp().user(), "WORKER"));
        String task = createWithTeam("Formwork", bobAccess, "CREW", anaAccess, "FOREMAN", carlaAccess, "LEAD");
        api.get(owner, tasks + "/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees.length()").value(3))
                .andExpect(jsonPath("$.assignees[0].accessId").value(carlaAccess.toString()))
                .andExpect(jsonPath("$.assignees[0].taskRole").value("LEAD"))
                .andExpect(jsonPath("$.assignees[1].accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignees[1].taskRole").value("FOREMAN"))
                .andExpect(jsonPath("$.assignees[2].accessId").value(bobAccess.toString()))
                .andExpect(jsonPath("$.assignees[2].taskRole").value("CREW"));
        assertEquals(3L, jdbc.queryForObject("select count(*) from activity_assignee where activity_id = ?",
                Long.class, UUID.fromString(task)));
    }

    @Test
    void teamNeedsExactlyOneLeadAndNobodyTwice() throws Exception {
        String start = "{\"name\":\"Bad\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"2027-02-01T18:00:00Z\",\"assignees\":";
        api.post(member, tasks, start + "[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(member, tasks, start + team(anaAccess, "CREW") + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(member, tasks, start + team(anaAccess, "LEAD", bobAccess, "LEAD") + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(member, tasks, start + team(anaAccess, "LEAD", anaAccess, "CREW") + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(member, tasks, start + "[{\"accessId\":\"" + anaAccess + "\",\"taskRole\":\"BOSS\"}]}")
                .andExpect(status().isBadRequest());
        api.post(member, tasks, start + "[{\"accessId\":\"" + anaAccess + "\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from activity a join goal g on g.id = a.goal_id "
                + "where g.project_id = ?", Long.class, projectId));
    }

    @Test
    void everyTeamMemberMovesTheTaskWhateverTheirRole() throws Exception {
        String task = createWithTeam("Shared", anaAccess, "LEAD", bobAccess, "CREW");
        api.get(bob, tasks).andExpect(jsonPath("$.length()").value(1));
        api.post(bob, tasks + "/" + task + "/start", null).andExpect(status().isOk());
        api.post(ana, tasks + "/" + task + "/complete", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void replacingTheTeamMovesTheRightToCompleteTheTask() throws Exception {
        String task = create(member, "Shared", anaAccess);
        api.put(member, tasks + "/" + task + "/assignees", "{\"assignees\":" + team(bobAccess, "LEAD", anaAccess, "CREW") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees[0].accessId").value(bobAccess.toString()))
                .andExpect(jsonPath("$.assignees[0].taskRole").value("LEAD"))
                .andExpect(jsonPath("$.assignees[1].accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignees[1].taskRole").value("CREW"));
        api.put(member, tasks + "/" + task + "/assignees", "{\"assignees\":" + team(bobAccess, "LEAD") + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignees.length()").value(1));
        api.put(member, tasks + "/" + task + "/assignees", "{\"assignees\":" + team(bobAccess, "CREW") + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.put(member, tasks + "/" + task + "/assignees", "{\"assignees\":[]}")
                .andExpect(status().isBadRequest());
        api.post(ana, tasks + "/" + task + "/complete", null).andExpect(status().isForbidden());
        api.post(bob, tasks + "/" + task + "/complete", null).andExpect(status().isOk());
        assertEquals(1L, jdbc.queryForObject("select count(*) from activity_assignee where activity_id = ?",
                Long.class, UUID.fromString(task)));
    }

    @Test
    void foreignAccessInTheTeamIsNotFound() throws Exception {
        String task = create(member, "Shared", anaAccess);
        String otherSite = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Other\"}"));
        UUID foreignAccess = members.grantSite(UUID.fromString(otherSite), members.ownerMembership(orgId, owner.user()));
        api.put(member, tasks + "/" + task + "/assignees", "{\"assignees\":" + team(anaAccess, "LEAD", foreignAccess, "CREW") + "}")
                .andExpect(status().isNotFound());
        api.get(owner, tasks + "/" + task).andExpect(jsonPath("$.assignees.length()").value(1));
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
        createWithTeam("Crew of B", anaAccess, "LEAD", bobAccess, "CREW");
        api.get(owner, tasks + "?assigneeAccessId=" + bobAccess).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void mineListsTheCallersTasksAcrossSites() throws Exception {
        create(member, "Tower task", anaAccess);
        create(member, "Bob task", bobAccess);
        String otherSite = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Other\"}"));
        UUID anaMembership = memberships.findByOrganizationIdAndUserAccountId(orgId, ana.user().getId()).orElseThrow().getId();
        UUID anaOther = members.grantSite(UUID.fromString(otherSite), anaMembership);
        String otherGoal = Api.id(api.post(owner, org + "/projects/" + otherSite + "/goals",
                "{\"name\":\"G\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
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
        createWithTeam("Crew task", bobAccess, "LEAD", anaAccess, "CREW");
        api.get(ana, org + "/activities/mine?status=TODO").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void financeOnlyViewsTasksAndOutsidersGetNotFound() throws Exception {
        String task = create(member, "Secret", anaAccess);
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        api.get(finance, tasks).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.get(finance, org + "/activities/mine").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.patch(finance, tasks + "/" + task, "{\"name\":\"x\"}").andExpect(status().isForbidden());
        api.post(finance, tasks + "/" + task + "/start", null).andExpect(status().isForbidden());

        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, tasks).andExpect(status().isNotFound());
        api.get(stranger, tasks + "/" + task).andExpect(status().isNotFound());
        api.post(stranger, tasks + "/" + task + "/complete", null).andExpect(status().isNotFound());
        api.get(stranger, "/api/organizations/" + strangerOrg + "/activities/mine")
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        String otherGoal = Api.id(api.post(owner, org + "/projects/" + projectId + "/goals",
                "{\"name\":\"Other phase\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
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

    @Test
    void colorIsOptionalSharedAndResettable() throws Exception {
        String task = create(member, "Pour", anaAccess);
        api.get(owner, tasks + "/" + task).andExpect(jsonPath("$.color").isEmpty());
        api.patch(member, tasks + "/" + task, "{\"color\":\"#FF8800\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#ff8800"));
        api.get(ana, tasks).andExpect(jsonPath("$[0].color").value("#ff8800"));
        api.patch(ana, tasks + "/" + task, "{\"color\":\"#000000\"}").andExpect(status().isForbidden());
        api.patch(member, tasks + "/" + task, "{\"color\":\"#ff88\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.patch(member, tasks + "/" + task, "{\"color\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.color").isEmpty());
    }
}

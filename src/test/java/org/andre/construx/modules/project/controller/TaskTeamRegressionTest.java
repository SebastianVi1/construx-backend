package org.andre.construx.modules.project.controller;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Team edge cases: rejected changes write nothing, role swaps keep rows, filters match any member. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskTeamRegressionTest {
    private static final String GOAL_START = "2027-01-01T00:00:00Z";
    private static final String GOAL_DUE = "2027-03-01T00:00:00Z";

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
    private Session owner, member, ana, bob, carl, dora;
    private UUID projectId, anaAccess, bobAccess, carlAccess, doraAccess;
    private String org, tasks;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        UUID orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        projectId = UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}")));
        String goalId = Api.id(api.post(owner, org + "/projects/" + projectId + "/goals",
                "{\"name\":\"Phase\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + GOAL_DUE + "\"}"));
        tasks = org + "/projects/" + projectId + "/goals/" + goalId + "/activities";
        member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        ana = api.signUp();
        anaAccess = members.grantSite(projectId, members.add(orgId, ana.user(), "WORKER"));
        bob = api.signUp();
        bobAccess = members.grantSite(projectId, members.add(orgId, bob.user(), "WORKER"));
        carl = api.signUp();
        carlAccess = members.grantSite(projectId, members.add(orgId, carl.user(), "WORKER"));
        dora = api.signUp();
        doraAccess = members.grantSite(projectId, members.add(orgId, dora.user(), "WORKER"));
    }

    private static String team(Object... accessAndRole) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < accessAndRole.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"accessId\":\"").append(accessAndRole[i])
                    .append("\",\"taskRole\":\"").append(accessAndRole[i + 1]).append("\"}");
        }
        return json.append("]").toString();
    }

    private static String createBody(String name, String due, String teamJson) {
        return "{\"name\":\"" + name + "\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + due
                + "\",\"assignees\":" + teamJson + "}";
    }

    private String create(String name, String due, Object... accessAndRole) throws Exception {
        return Api.id(api.post(member, tasks, createBody(name, due, team(accessAndRole))).andExpect(status().isCreated()));
    }

    private String put(Object... accessAndRole) {
        return "{\"assignees\":" + team(accessAndRole) + "}";
    }

    private List<Map<String, Object>> rows(String task) {
        return jdbc.queryForList("select id, access_id, task_role from activity_assignee where activity_id = ? "
                + "order by access_id", UUID.fromString(task));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void createWithThreeRolesIsSortedLeadForemanCrew() throws Exception {
        String task = create("Formwork", "2027-02-01T00:00:00Z", carlAccess, "CREW", bobAccess, "FOREMAN", anaAccess, "LEAD");
        api.get(owner, tasks + "/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.assignees[0].taskRole").value("LEAD"))
                .andExpect(jsonPath("$.assignees[0].accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignees[0].membershipId").isNotEmpty())
                .andExpect(jsonPath("$.assignees[1].taskRole").value("FOREMAN"))
                .andExpect(jsonPath("$.assignees[2].taskRole").value("CREW"));
    }

    @Test
    void rejectedCreatesWriteNothing() throws Exception {
        long acts = count("activity"), rows = count("activity_assignee");
        String due = "2027-02-01T00:00:00Z";
        api.post(member, tasks, createBody("x", due, "[]")).andExpect(status().isBadRequest());
        api.post(member, tasks, createBody("x", due, team(anaAccess, "CREW", bobAccess, "FOREMAN"))).andExpect(status().isBadRequest());
        api.post(member, tasks, createBody("x", due, team(anaAccess, "LEAD", bobAccess, "LEAD"))).andExpect(status().isBadRequest());
        api.post(member, tasks, createBody("x", due, team(anaAccess, "LEAD", anaAccess, "LEAD"))).andExpect(status().isBadRequest());
        api.post(member, tasks, createBody("x", due, team(anaAccess, "LEAD", bobAccess, "BOSS"))).andExpect(status().isBadRequest());
        api.post(member, tasks, createBody("x", due, "[null]")).andExpect(status().isBadRequest());
        api.post(member, tasks, "{\"name\":\"x\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"" + due
                + "\",\"assigneeAccessId\":\"" + anaAccess + "\"}").andExpect(status().isBadRequest());
        assertEquals(acts, count("activity"));
        assertEquals(rows, count("activity_assignee"));
    }

    @Test
    void rejectedPutsLeaveTheTeamUntouched() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "CREW");
        List<Map<String, Object>> before = rows(task);
        String path = tasks + "/" + task + "/assignees";
        api.put(member, path, "{\"assignees\":[]}").andExpect(status().isBadRequest());
        api.put(member, path, put(anaAccess, "CREW")).andExpect(status().isBadRequest());
        api.put(member, path, put(anaAccess, "LEAD", carlAccess, "LEAD")).andExpect(status().isBadRequest());
        api.put(member, path, put(carlAccess, "LEAD", carlAccess, "CREW")).andExpect(status().isBadRequest());
        api.put(member, path, put(carlAccess, "LEAD", doraAccess, "KING")).andExpect(status().isBadRequest());
        api.put(member, path, put(carlAccess, "LEAD", UUID.randomUUID(), "CREW")).andExpect(status().isNotFound());
        api.put(member, tasks + "/" + task + "/assignee", "{\"assigneeAccessId\":\"" + carlAccess + "\"}")
                .andExpect(r -> assertTrue(r.getResponse().getStatus() >= 400));
        assertEquals(before, rows(task));
    }

    @Test
    void replaceTeamMovesRightsAndLeadWorkerCannotManageTeam() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "CREW");
        api.put(ana, tasks + "/" + task + "/assignees", put(anaAccess, "LEAD", carlAccess, "CREW"))
                .andExpect(status().isForbidden());
        api.put(member, tasks + "/" + task + "/assignees", put(carlAccess, "LEAD", doraAccess, "CREW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignees.length()").value(2));
        api.post(ana, tasks + "/" + task + "/start", null).andExpect(status().isForbidden());
        api.post(bob, tasks + "/" + task + "/complete", null).andExpect(status().isForbidden());
        api.post(dora, tasks + "/" + task + "/start", null).andExpect(status().isOk());
        api.post(carl, tasks + "/" + task + "/complete", null).andExpect(status().isOk());
    }

    @Test
    void swapLeadAndChangeRolesInOneRequestKeepsRows() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "FOREMAN", carlAccess, "CREW");
        Map<Object, Object> idsBefore = new java.util.HashMap<>();
        rows(task).forEach(r -> idsBefore.put(r.get("access_id"),
                r.get("id")));
        api.put(member, tasks + "/" + task + "/assignees", put(bobAccess, "LEAD", anaAccess, "CREW", doraAccess, "FOREMAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignees[0].accessId").value(bobAccess.toString()))
                .andExpect(jsonPath("$.assignees[0].taskRole").value("LEAD"))
                .andExpect(jsonPath("$.assignees[1].accessId").value(doraAccess.toString()))
                .andExpect(jsonPath("$.assignees[2].accessId").value(anaAccess.toString()))
                .andExpect(jsonPath("$.assignees[2].taskRole").value("CREW"));
        List<Map<String, Object>> after = rows(task);
        assertEquals(3, after.size());
        for (Map<String, Object> r : after) {
            Object access = r.get("access_id");
            Object id = r.get("id");
            if (access.equals(anaAccess) || access.equals(bobAccess)) {
                assertEquals(idsBefore.get(access), id, "staying member keeps its row");
            }
            assertNotEquals(carlAccess, access);
        }
        assertEquals(1L, jdbc.queryForObject("select count(*) from activity_assignee where activity_id = ? and task_role = 'LEAD'",
                Long.class, UUID.fromString(task)));
        // Swap back again, same request touching every row.
        api.put(member, tasks + "/" + task + "/assignees", put(anaAccess, "LEAD", bobAccess, "CREW", doraAccess, "CREW"))
                .andExpect(status().isOk());
    }

    @Test
    void filtersAndMineMatchAnyMemberWithoutDuplicateRows() throws Exception {
        create("Late", "2027-02-20T00:00:00Z", anaAccess, "LEAD", bobAccess, "CREW", carlAccess, "FOREMAN");
        create("Early", "2027-01-10T00:00:00Z", bobAccess, "LEAD", anaAccess, "FOREMAN", carlAccess, "CREW");
        create("Mid", "2027-02-01T00:00:00Z", carlAccess, "LEAD", anaAccess, "CREW");
        create("Other", "2027-02-02T00:00:00Z", doraAccess, "LEAD");
        api.get(owner, tasks).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("Early"))
                .andExpect(jsonPath("$[1].name").value("Mid"))
                .andExpect(jsonPath("$[2].name").value("Other"))
                .andExpect(jsonPath("$[3].name").value("Late"))
                .andExpect(jsonPath("$[0].assignees.length()").value(3));
        api.get(owner, tasks + "?assigneeAccessId=" + anaAccess).andExpect(jsonPath("$.length()").value(3));
        api.get(owner, tasks + "?assigneeAccessId=" + doraAccess).andExpect(jsonPath("$.length()").value(1));
        api.get(ana, org + "/activities/mine").andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Early"))
                .andExpect(jsonPath("$[2].name").value("Late"))
                .andExpect(jsonPath("$[0].assignees.length()").value(3));
        api.get(dora, org + "/activities/mine").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void siteAccessRemovalBlockedWhileOnAnyTeam() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "CREW");
        String access = org + "/projects/" + projectId + "/access/";
        api.delete(owner, access + bobAccess).andExpect(status().isConflict());
        api.delete(owner, access + anaAccess).andExpect(status().isConflict());
        api.put(member, tasks + "/" + task + "/assignees", put(anaAccess, "LEAD")).andExpect(status().isOk());
        api.delete(owner, access + bobAccess).andExpect(status().isNoContent());
    }

    @Test
    void deletingATaskWithATeamRemovesItsRows() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "FOREMAN", carlAccess, "CREW");
        api.delete(member, tasks + "/" + task).andExpect(status().isNoContent());
        assertEquals(0L, jdbc.queryForObject("select count(*) from activity_assignee where activity_id = ?",
                Long.class, UUID.fromString(task)));
        api.delete(owner, org + "/projects/" + projectId + "/access/" + carlAccess).andExpect(status().isNoContent());
    }

    @Test
    void ownScopeNonTeamWorkerCannotMoveTask() throws Exception {
        String task = create("T", "2027-02-01T00:00:00Z", anaAccess, "LEAD", bobAccess, "CREW");
        api.post(carl, tasks + "/" + task + "/start", null).andExpect(status().isForbidden());
        api.post(carl, tasks + "/" + task + "/complete", null).andExpect(status().isForbidden());
        api.get(carl, org + "/activities/mine").andExpect(jsonPath("$.length()").value(0));
        assertEquals("TODO", JsonPath.read(api.get(owner, tasks + "/" + task).andReturn().getResponse()
                .getContentAsString(), "$.status"));
    }
}

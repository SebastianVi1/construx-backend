package org.andre.construx.modules.finance.controller;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Edge cases of the task Lead's expense rights (ADR 0005): roles, cross-task, cross-site, unchanged data. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeadExpenseRegressionTest {
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
    private Session owner, finance, admin, ana, bob, carl, dan;
    private UUID orgId, siteId, site2Id, anaAccess, bobAccess, carlAccess, danAccess, ana2Access;
    private String site, site2, goal, goal2, otherSiteGoal, task, task2, otherSiteTask;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        String base = "/api/organizations/" + orgId + "/projects";
        siteId = UUID.fromString(Api.id(api.post(owner, base, "{\"name\":\"Tower\"}")));
        site2Id = UUID.fromString(Api.id(api.post(owner, base, "{\"name\":\"Bridge\"}")));
        site = base + "/" + siteId;
        site2 = base + "/" + site2Id;
        goal = newGoal(site, "Structure");
        goal2 = newGoal(site, "Finishes");
        otherSiteGoal = newGoal(site2, "Piers");

        finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        admin = api.signUp();
        members.grantSite(siteId, members.add(orgId, admin.user(), "ADMINISTRATOR"));
        ana = api.signUp();
        UUID anaM = members.add(orgId, ana.user(), "WORKER");
        anaAccess = members.grantSite(siteId, anaM);
        ana2Access = members.grantSite(site2Id, anaM);
        bob = api.signUp();
        bobAccess = members.grantSite(siteId, members.add(orgId, bob.user(), "WORKER"));
        carl = api.signUp();
        carlAccess = members.grantSite(siteId, members.add(orgId, carl.user(), "WORKER"));
        dan = api.signUp();
        danAccess = members.grantSite(siteId, members.add(orgId, dan.user(), "MEMBER"));

        api.put(owner, site + "/budget", "{\"allocatedBudget\":10000}").andExpect(status().isOk());
        api.put(owner, goal + "/budget", "{\"allocatedBudget\":5000}").andExpect(status().isOk());
        task = createTask(goal, "Pour slab", anaAccess, "LEAD", bobAccess, "FOREMAN", carlAccess, "CREW");
        api.put(owner, task + "/budget", "{\"allocatedBudget\":1000}").andExpect(status().isOk());
        task2 = createTask(goal2, "Paint", bobAccess, "LEAD", anaAccess, "CREW");
        otherSiteTask = createTask(otherSiteGoal, "Pier 1", ana2Access, "LEAD");
    }

    private String newGoal(String s, String name) throws Exception {
        return s + "/goals/" + Api.id(api.post(owner, s + "/goals", "{\"name\":\"" + name
                + "\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
    }

    private String createTask(String g, String name, Object... accessAndRole) throws Exception {
        return g + "/activities/" + Api.id(api.post(owner, g + "/activities", "{\"name\":\"" + name
                + "\",\"startAt\":\"2027-01-02T00:00:00Z\",\"dueAt\":\"2027-01-10T00:00:00Z\",\"assignees\":"
                + team(accessAndRole) + "}").andExpect(status().isCreated()));
    }

    private static String team(Object... accessAndRole) {
        StringBuilder team = new StringBuilder("[");
        for (int i = 0; i < accessAndRole.length; i += 2) {
            team.append(i == 0 ? "" : ",").append("{\"accessId\":\"").append(accessAndRole[i])
                    .append("\",\"taskRole\":\"").append(accessAndRole[i + 1]).append("\"}");
        }
        return team.append("]").toString();
    }

    private static String expense(String amount, String category) {
        return "{\"amount\":" + amount + ",\"spentOn\":\"2027-01-03\",\"description\":\"Item\",\"category\":\""
                + category + "\"}";
    }

    private String record(Session as, String t, String amount) throws Exception {
        return Api.id(api.post(as, t + "/expenses", expense(amount, "MATERIALS")).andExpect(status().isCreated()));
    }

    private String fingerprint() {
        return jdbc.queryForObject("select count(*) || ':' || coalesce(sum(amount), 0) from expense", String.class);
    }

    @Test
    void everyCategoryCountsAgainstTaskAndSite() throws Exception {
        int i = 1;
        for (String c : new String[] {"MATERIALS", "LABOR", "EQUIPMENT", "OTHER"}) {
            api.post(ana, task + "/expenses", expense(String.valueOf(100 * i++), c)).andExpect(status().isCreated());
        }
        api.get(ana, task + "/budget").andExpect(jsonPath("$.spent").value(1000)).andExpect(jsonPath("$.remaining").value(0));
        api.get(owner, site + "/budget").andExpect(jsonPath("$.spent").value(1000))
                .andExpect(jsonPath("$.remaining").value(9000))
                .andExpect(jsonPath("$.byCategory[1].amount").value(200))
                .andExpect(jsonPath("$.byCategory[3].amount").value(400));
        api.get(owner, site + "/expenses").andExpect(jsonPath("$.length()").value(4));
        api.get(owner, site2 + "/budget").andExpect(jsonPath("$.spent").value(0));
    }

    @Test
    void foremanCrewNonTeamAndMemberGet403AndDataIsUnchanged() throws Exception {
        record(ana, task, "100");
        String before = fingerprint();
        for (Session s : new Session[] {bob, carl, dan}) {
            api.get(s, task + "/expenses").andExpect(status().isForbidden());
            api.post(s, task + "/expenses", expense("10", "LABOR")).andExpect(status().isForbidden());
            api.get(s, task + "/budget").andExpect(status().isForbidden());
        }
        assertEquals(before, fingerprint());
    }

    @Test
    void leadCannotEditOwnExpenseOnATaskTheyNoLongerLeadEvenWhileLeadingAnother() throws Exception {
        String onTask = record(ana, task, "100");
        // Ana stays Lead of otherSiteTask (other site) and becomes Lead of a new task on the same site.
        createTask(goal2, "Tiles", anaAccess, "LEAD");
        api.put(owner, task + "/assignees", "{\"assignees\":" + team(bobAccess, "LEAD", anaAccess, "CREW") + "}")
                .andExpect(status().isOk());
        String before = fingerprint();
        api.patch(ana, site + "/expenses/" + onTask, "{\"amount\":1}").andExpect(status().isForbidden());
        api.delete(ana, site + "/expenses/" + onTask).andExpect(status().isForbidden());
        api.patch(bob, site + "/expenses/" + onTask, "{\"amount\":1}").andExpect(status().isForbidden());
        assertEquals(before, fingerprint());
    }

    @Test
    void leadOfAnotherTaskCannotEditExpensesOfATaskWhereTheyAreCrew() throws Exception {
        String bobOnTask2 = record(bob, task2, "50");
        String before = fingerprint();
        api.patch(ana, site + "/expenses/" + bobOnTask2, "{\"amount\":1}").andExpect(status().isForbidden());
        api.delete(ana, site + "/expenses/" + bobOnTask2).andExpect(status().isForbidden());
        api.get(ana, task2 + "/expenses").andExpect(status().isForbidden());
        assertEquals(before, fingerprint());
    }

    @Test
    void mismatchedSitePathCannotReachAnExpense() throws Exception {
        String own = record(ana, task, "100");
        String onSite2 = record(ana, otherSiteTask, "70");
        String before = fingerprint();
        api.patch(ana, site2 + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isForbidden());
        api.delete(ana, site + "/expenses/" + onSite2).andExpect(status().isForbidden());
        api.patch(finance, site2 + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isNotFound());
        assertEquals(before, fingerprint());
        api.patch(ana, site2 + "/expenses/" + onSite2, "{\"amount\":80}").andExpect(status().isOk());
    }

    @Test
    void mismatchedGoalOrSiteOnTaskPathsIs404AndChargesNothing() throws Exception {
        String taskId = task.substring(task.lastIndexOf('/') + 1);
        String before = fingerprint();
        api.post(ana, goal2 + "/activities/" + taskId + "/expenses", expense("10", "OTHER")).andExpect(status().isNotFound());
        api.post(ana, site2 + "/goals/" + goal.substring(goal.lastIndexOf('/') + 1) + "/activities/" + taskId
                + "/expenses", expense("10", "OTHER")).andExpect(status().isNotFound());
        api.post(ana, otherSiteGoal + "/activities/" + taskId + "/expenses", expense("10", "OTHER"))
                .andExpect(status().isNotFound());
        api.get(ana, goal2 + "/activities/" + taskId + "/budget").andExpect(status().isNotFound());
        assertEquals(before, fingerprint());
    }

    @Test
    void leadCannotTouchSiteBudgetOrSiteExpenses() throws Exception {
        String before = fingerprint();
        api.post(ana, site + "/expenses", expense("10", "OTHER")).andExpect(status().isForbidden());
        api.put(ana, site + "/budget", "{\"allocatedBudget\":1}").andExpect(status().isForbidden());
        api.put(ana, goal + "/budget", "{\"allocatedBudget\":1}").andExpect(status().isForbidden());
        api.put(ana, task + "/budget", "{\"allocatedBudget\":1}").andExpect(status().isForbidden());
        api.get(ana, site + "/budget").andExpect(status().isForbidden());
        assertEquals(before, fingerprint());
    }

    @Test
    void administratorReadsTaskMoneyButCannotRecordUnlessLead() throws Exception {
        record(ana, task, "100");
        api.get(admin, task + "/expenses").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.get(admin, task + "/budget").andExpect(status().isOk()).andExpect(jsonPath("$.spent").value(100));
        api.post(admin, task + "/expenses", expense("10", "OTHER")).andExpect(status().isForbidden());
        api.post(finance, task + "/expenses", expense("10", "OTHER")).andExpect(status().isCreated());
    }

    @Test
    void phaseDeleteStillConflictsWhileItHasTasks() throws Exception {
        api.delete(owner, goal2).andExpect(status().isConflict());
        String own = record(ana, task, "100");
        api.delete(owner, task).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        api.delete(finance, site + "/expenses/" + own).andExpect(status().isNoContent());
        api.delete(owner, task).andExpect(status().isNoContent());
    }

    @Test
    void suspendedLeadLosesRights() throws Exception {
        String own = record(ana, task, "100");
        jdbc.update("update organization_membership set status = 'SUSPENDED' where user_account_id = ?",
                ana.user().getId());
        api.get(ana, task + "/budget").andExpect(status().isNotFound());
        api.patch(ana, site + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isNotFound());
        api.delete(ana, site + "/expenses/" + own).andExpect(status().isNotFound());
    }

    /** Baseline 95b5f86: a non-member gets 404 (MembershipGuard) on site expense PATCH/DELETE. */
    @Test
    void nonMemberEditOrDeleteOfASiteExpenseIs404() throws Exception {
        String own = record(ana, task, "100");
        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String before = fingerprint();
        api.patch(stranger, site + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isNotFound());
        api.delete(stranger, site + "/expenses/" + own).andExpect(status().isNotFound());
        api.patch(stranger, site + "/expenses/" + UUID.randomUUID(), "{\"amount\":1}").andExpect(status().isNotFound());
        assertEquals(before, fingerprint());
    }
}

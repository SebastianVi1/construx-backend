package org.andre.construx.modules.project.controller;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task budgets are carved out of their phase budget; only Owner and Finance change them. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskBudgetFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private UUID projectId;
    private UUID assignee;
    private String site;
    private String phase;
    private String tasks;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        projectId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        site = "/api/organizations/" + orgId + "/projects/" + projectId;
        assignee = members.grantSite(projectId, members.ownerMembership(orgId, owner.user()));
        phase = site + "/goals/" + Api.id(api.post(owner, site + "/goals",
                "{\"name\":\"Foundations\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        tasks = phase + "/activities";
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        members.grantSite(projectId, members.add(orgId, session.user(), role));
        return session;
    }

    private String task(String name) throws Exception {
        return tasks + "/" + Api.id(api.post(owner, tasks, "{\"name\":\"" + name + "\",\"startAt\":\"2027-01-02T00:00:00Z\","
                + "\"dueAt\":\"2027-01-10T00:00:00Z\",\"assigneeAccessId\":\"" + assignee + "\"}"));
    }

    private static String budget(String amount) {
        return "{\"allocatedBudget\":" + amount + "}";
    }

    @Test
    void financeAssignsTaskBudgetsAndThePhaseShowsWhatIsLeft() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        String task = task("Dig");
        api.get(owner, task).andExpect(jsonPath("$.allocatedBudget").isEmpty());

        Session finance = as("FINANCE");
        api.put(finance, task + "/budget", budget("120.25")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(120.25));
        api.get(finance, tasks).andExpect(jsonPath("$[0].allocatedBudget").value(120.25));
        api.get(owner, phase).andExpect(jsonPath("$.allocatedToActivities").value(120.25))
                .andExpect(jsonPath("$.unallocated").value(379.75));
        api.put(finance, task + "/budget", budget("null")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").isEmpty());
        api.get(owner, phase).andExpect(jsonPath("$.unallocated").value(500));
    }

    @Test
    void tasksCannotTakeMoreThanThePhaseBudget() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        String a = task("Dig");
        String b = task("Pour");
        api.put(owner, a + "/budget", budget("400")).andExpect(status().isOk());
        api.put(owner, b + "/budget", budget("100.01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
        api.put(owner, b + "/budget", budget("100")).andExpect(status().isOk());
        api.put(owner, a + "/budget", budget("400")).andExpect(status().isOk());
    }

    @Test
    void aTaskBudgetNeedsAPhaseBudget() throws Exception {
        String task = task("Dig");
        api.put(owner, task + "/budget", budget("1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_PARENT_MISSING"));
    }

    @Test
    void thePhaseBudgetCannotDropBelowWhatItsTasksHold() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        api.put(owner, task("Dig") + "/budget", budget("300")).andExpect(status().isOk());
        api.put(owner, phase + "/budget", budget("299")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
        api.put(owner, phase + "/budget", budget("null")).andExpect(status().isBadRequest());
        api.put(owner, phase + "/budget", budget("300")).andExpect(status().isOk());
    }

    @Test
    void deletingATaskReleasesItsBudget() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        String task = task("Dig");
        api.put(owner, task + "/budget", budget("500")).andExpect(status().isOk());
        api.delete(owner, task).andExpect(status().isNoContent());
        api.get(owner, phase).andExpect(jsonPath("$.unallocated").value(500));
    }

    @Test
    void administratorMemberAndWorkerCannotChangeATaskBudget() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        String task = task("Dig");
        api.put(owner, task + "/budget", budget("50")).andExpect(status().isOk());
        for (String role : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER"}) {
            api.put(as(role), task + "/budget", budget("1")).andExpect(status().isForbidden());
        }
        api.get(as("ADMINISTRATOR"), task).andExpect(jsonPath("$.allocatedBudget").value(50));
        api.get(as("MEMBER"), tasks).andExpect(jsonPath("$[0].allocatedBudget").isEmpty());
    }

    @Test
    void invalidBodiesAndPatchesLeaveTheBudgetAlone() throws Exception {
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        String task = task("Dig");
        api.put(owner, task + "/budget", budget("50")).andExpect(status().isOk());
        api.put(owner, task + "/budget", budget("-1")).andExpect(status().isBadRequest());
        api.put(owner, task + "/budget", budget("1.234")).andExpect(status().isBadRequest());
        api.put(owner, task + "/budget", "{}").andExpect(status().isBadRequest());
        api.patch(owner, task, "{\"name\":\"Renamed\",\"allocatedBudget\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(50));
        api.put(owner, phase + "/activities/" + UUID.randomUUID() + "/budget", budget("1"))
                .andExpect(status().isNotFound());
    }
}

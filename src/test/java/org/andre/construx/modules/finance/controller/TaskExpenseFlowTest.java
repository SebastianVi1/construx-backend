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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Lead of a task records expenses on it and sees its spending, whatever their organization role (ADR 0005). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskExpenseFlowTest {
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
    private Session owner, finance, ana, bob, outsider;
    private UUID orgId, anaAccess, bobAccess, anaMembership;
    private String site, goal, task;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        String base = "/api/organizations/" + orgId + "/projects";
        String siteId = Api.id(api.post(owner, base, "{\"name\":\"Tower\"}"));
        site = base + "/" + siteId;
        goal = site + "/goals/" + Api.id(api.post(owner, site + "/goals",
                "{\"name\":\"Structure\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));

        finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        ana = api.signUp();
        anaMembership = members.add(orgId, ana.user(), "WORKER");
        anaAccess = members.grantSite(UUID.fromString(siteId), anaMembership);
        bob = api.signUp();
        bobAccess = members.grantSite(UUID.fromString(siteId), members.add(orgId, bob.user(), "WORKER"));
        outsider = api.signUp();
        members.grantSite(UUID.fromString(siteId), members.add(orgId, outsider.user(), "WORKER"));

        api.put(owner, site + "/budget", "{\"allocatedBudget\":10000}").andExpect(status().isOk());
        api.put(owner, goal + "/budget", "{\"allocatedBudget\":5000}").andExpect(status().isOk());
        task = createTask("Pour slab", anaAccess, "LEAD", bobAccess, "CREW");
        api.put(owner, task + "/budget", "{\"allocatedBudget\":1000}").andExpect(status().isOk());
    }

    private String createTask(String name, Object... accessAndRole) throws Exception {
        StringBuilder team = new StringBuilder("[");
        for (int i = 0; i < accessAndRole.length; i += 2) {
            team.append(i == 0 ? "" : ",").append("{\"accessId\":\"").append(accessAndRole[i])
                    .append("\",\"taskRole\":\"").append(accessAndRole[i + 1]).append("\"}");
        }
        return goal + "/activities/" + Api.id(api.post(owner, goal + "/activities", "{\"name\":\"" + name
                + "\",\"startAt\":\"2027-01-02T00:00:00Z\",\"dueAt\":\"2027-01-10T00:00:00Z\",\"assignees\":"
                + team.append("]") + "}").andExpect(status().isCreated()));
    }

    private static String expense(String amount, String description, String category) {
        return "{\"amount\":" + amount + ",\"spentOn\":\"2027-01-03\",\"description\":\"" + description
                + "\",\"category\":\"" + category + "\"}";
    }

    private String record(Session as, String amount, String description) throws Exception {
        return Api.id(api.post(as, task + "/expenses", expense(amount, description, "MATERIALS"))
                .andExpect(status().isCreated()));
    }

    @Test
    void leadRecordsExpensesOfAnyCategoryThatCountForTheTaskAndTheSite() throws Exception {
        api.post(ana, task + "/expenses", expense("300", "Cement", "MATERIALS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value(task.substring(task.lastIndexOf('/') + 1)))
                .andExpect(jsonPath("$.createdByMembershipId").value(anaMembership.toString()))
                .andExpect(jsonPath("$.category").value("MATERIALS"));
        api.post(ana, task + "/expenses", expense("200", "Mixer rental", "EQUIPMENT")).andExpect(status().isCreated());

        api.get(ana, task + "/budget").andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(1000))
                .andExpect(jsonPath("$.spent").value(500))
                .andExpect(jsonPath("$.remaining").value(500));
        api.get(ana, task + "/expenses").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").isNotEmpty());
        api.get(owner, site + "/budget").andExpect(jsonPath("$.spent").value(500));
        api.get(finance, task + "/expenses").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        api.get(ana, site + "/budget").andExpect(status().isForbidden());
        api.get(ana, site + "/expenses").andExpect(status().isForbidden());
    }

    @Test
    void overrunAndTasksWithoutBudgetAreAllowed() throws Exception {
        record(ana, "1500", "Steel");
        api.get(ana, task + "/budget").andExpect(jsonPath("$.spent").value(1500))
                .andExpect(jsonPath("$.remaining").value(-500));

        String unbudgeted = createTask("Paint", anaAccess, "LEAD");
        api.post(ana, unbudgeted + "/expenses", expense("50", "Brushes", "OTHER")).andExpect(status().isCreated());
        api.get(ana, unbudgeted + "/budget").andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").isEmpty())
                .andExpect(jsonPath("$.spent").value(50))
                .andExpect(jsonPath("$.remaining").isEmpty());
    }

    @Test
    void otherTeamMembersAndOutsidersCannotSeeOrRecordMoney() throws Exception {
        record(ana, "100", "Cement");
        for (Session someone : new Session[] {bob, outsider}) {
            api.post(someone, task + "/expenses", expense("10", "Nails", "MATERIALS")).andExpect(status().isForbidden());
            api.get(someone, task + "/expenses").andExpect(status().isForbidden());
            api.get(someone, task + "/budget").andExpect(status().isForbidden());
        }
        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, task + "/expenses").andExpect(status().isNotFound());
        api.post(stranger, task + "/expenses", expense("10", "Nails", "MATERIALS")).andExpect(status().isNotFound());
        api.get(owner, task + "/expenses").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void leadEditsAndDeletesOnlyTheExpensesTheyRecorded() throws Exception {
        String own = record(ana, "100", "Cement");
        String byFinance = record(finance, "200", "Rebar");
        String siteOnly = Api.id(api.post(finance, site + "/expenses", expense("50", "Permit", "OTHER")));

        api.patch(ana, site + "/expenses/" + byFinance, "{\"amount\":1}").andExpect(status().isForbidden());
        api.delete(ana, site + "/expenses/" + byFinance).andExpect(status().isForbidden());
        api.patch(ana, site + "/expenses/" + siteOnly, "{\"amount\":1}").andExpect(status().isForbidden());
        api.patch(ana, site + "/expenses/" + UUID.randomUUID(), "{\"amount\":1}").andExpect(status().isForbidden());
        api.patch(ana, site + "/expenses/" + own, "{\"amount\":120,\"description\":\"Cement bags\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(120))
                .andExpect(jsonPath("$.activityId").isNotEmpty());
        api.patch(finance, site + "/expenses/" + own, "{\"amount\":130}").andExpect(status().isOk());
        api.delete(ana, site + "/expenses/" + own).andExpect(status().isNoContent());
        api.get(owner, task + "/expenses").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void formerLeadLosesTheMoneyRightsAndTheNewLeadCannotEditTheirExpenses() throws Exception {
        String own = record(ana, "100", "Cement");
        api.put(owner, task + "/assignees", "{\"assignees\":[{\"accessId\":\"" + bobAccess + "\",\"taskRole\":\"LEAD\"},"
                + "{\"accessId\":\"" + anaAccess + "\",\"taskRole\":\"CREW\"}]}").andExpect(status().isOk());

        api.post(ana, task + "/expenses", expense("10", "Nails", "MATERIALS")).andExpect(status().isForbidden());
        api.get(ana, task + "/budget").andExpect(status().isForbidden());
        api.patch(ana, site + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isForbidden());
        api.patch(bob, site + "/expenses/" + own, "{\"amount\":1}").andExpect(status().isForbidden());
        api.get(bob, task + "/budget").andExpect(status().isOk()).andExpect(jsonPath("$.spent").value(100));
        record(bob, "20", "Wire");
    }

    @Test
    void leadCannotChangeTheTaskAllocationOrItsTeam() throws Exception {
        api.put(ana, task + "/budget", "{\"allocatedBudget\":4000}").andExpect(status().isForbidden());
        api.put(ana, task + "/assignees", "{\"assignees\":[{\"accessId\":\"" + anaAccess + "\",\"taskRole\":\"LEAD\"}]}")
                .andExpect(status().isForbidden());
        api.get(ana, task).andExpect(status().isOk()).andExpect(jsonPath("$.allocatedBudget").isEmpty());
        api.get(ana, task + "/budget").andExpect(jsonPath("$.allocatedBudget").value(1000));
    }

    @Test
    void aTaskWithExpensesCannotBeDeleted() throws Exception {
        String own = record(ana, "100", "Cement");
        api.delete(owner, task).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        api.delete(ana, site + "/expenses/" + own).andExpect(status().isNoContent());
        api.delete(owner, task).andExpect(status().isNoContent());
    }

    @Test
    void invalidTaskExpensesAreRejected() throws Exception {
        api.post(ana, task + "/expenses", expense("0", "Free", "MATERIALS")).andExpect(status().isBadRequest());
        api.post(ana, task + "/expenses", expense("10", "Food", "FOOD")).andExpect(status().isBadRequest());
        api.post(ana, goal + "/activities/" + UUID.randomUUID() + "/expenses", expense("10", "Nails", "MATERIALS"))
                .andExpect(status().isNotFound());
        api.get(owner, task + "/expenses").andExpect(jsonPath("$.length()").value(0));
    }
}

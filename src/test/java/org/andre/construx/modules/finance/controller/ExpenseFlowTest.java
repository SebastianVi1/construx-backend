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

/** Site expenses are subtracted from the allocated budget; only budget roles read or write them. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseFlowTest {
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
    private String site;
    private String siteId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        String base = "/api/organizations/" + orgId + "/projects";
        siteId = Api.id(api.post(owner, base, "{\"name\":\"Tower\"}"));
        site = base + "/" + siteId;
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        UUID membership = members.add(orgId, session.user(), role);
        members.grantSite(UUID.fromString(siteId), membership);
        return session;
    }

    private String expense(String amount, String date, String description, String category) {
        return "{\"amount\":" + amount + ",\"spentOn\":\"" + date + "\",\"description\":\"" + description
                + "\",\"category\":\"" + category + "\"}";
    }

    @Test
    void expensesAreSubtractedFromTheBudgetAndGroupedByCategory() throws Exception {
        api.put(owner, site + "/budget", "{\"allocatedBudget\":10000}").andExpect(status().isOk());
        api.post(owner, site + "/expenses", expense("2500.5", "2026-10-01", " Cement ", "MATERIALS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.projectId").value(siteId))
                .andExpect(jsonPath("$.amount").value(2500.5))
                .andExpect(jsonPath("$.spentOn").value("2026-10-01"))
                .andExpect(jsonPath("$.description").value("Cement"))
                .andExpect(jsonPath("$.category").value("MATERIALS"));
        api.post(owner, site + "/expenses", expense("1000", "2026-10-03", "Crew", "LABOR")).andExpect(status().isCreated());
        api.post(owner, site + "/expenses", expense("499.5", "2026-10-02", "Sand", "MATERIALS")).andExpect(status().isCreated());

        api.get(owner, site + "/budget").andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(10000))
                .andExpect(jsonPath("$.spent").value(4000))
                .andExpect(jsonPath("$.remaining").value(6000))
                .andExpect(jsonPath("$.byCategory.length()").value(4))
                .andExpect(jsonPath("$.byCategory[0].category").value("MATERIALS"))
                .andExpect(jsonPath("$.byCategory[0].amount").value(3000))
                .andExpect(jsonPath("$.byCategory[1].category").value("LABOR"))
                .andExpect(jsonPath("$.byCategory[1].amount").value(1000))
                .andExpect(jsonPath("$.byCategory[2].category").value("EQUIPMENT"))
                .andExpect(jsonPath("$.byCategory[2].amount").value(0))
                .andExpect(jsonPath("$.byCategory[3].category").value("OTHER"));
        // Newest first
        api.get(owner, site + "/expenses").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].description").value("Crew"))
                .andExpect(jsonPath("$[2].description").value("Cement"));
    }

    @Test
    void overrunGivesANegativeRemainingAndNoBudgetGivesNull() throws Exception {
        api.post(owner, site + "/expenses", expense("150", "2026-10-01", "Tools", "EQUIPMENT")).andExpect(status().isCreated());
        api.get(owner, site + "/budget")
                .andExpect(jsonPath("$.allocatedBudget").isEmpty())
                .andExpect(jsonPath("$.spent").value(150))
                .andExpect(jsonPath("$.remaining").isEmpty());
        api.put(owner, site + "/budget", "{\"allocatedBudget\":100}").andExpect(status().isOk());
        api.get(owner, site + "/budget").andExpect(jsonPath("$.remaining").value(-50));
    }

    @Test
    void editingAndDeletingAnExpenseUpdatesTheTotals() throws Exception {
        api.put(owner, site + "/budget", "{\"allocatedBudget\":1000}").andExpect(status().isOk());
        String id = Api.id(api.post(owner, site + "/expenses", expense("300", "2026-10-01", "Paint", "OTHER")));
        api.patch(owner, site + "/expenses/" + id, "{\"amount\":400,\"category\":\"MATERIALS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(400))
                .andExpect(jsonPath("$.category").value("MATERIALS"))
                .andExpect(jsonPath("$.description").value("Paint"));
        api.get(owner, site + "/budget").andExpect(jsonPath("$.remaining").value(600));
        api.delete(owner, site + "/expenses/" + id).andExpect(status().isNoContent());
        api.get(owner, site + "/budget").andExpect(jsonPath("$.spent").value(0)).andExpect(jsonPath("$.remaining").value(1000));
        api.delete(owner, site + "/expenses/" + id).andExpect(status().isNotFound());
    }

    @Test
    void invalidExpensesAreRejected() throws Exception {
        api.post(owner, site + "/expenses", expense("0", "2026-10-01", "x", "OTHER")).andExpect(status().isBadRequest());
        api.post(owner, site + "/expenses", expense("1.234", "2026-10-01", "x", "OTHER")).andExpect(status().isBadRequest());
        api.post(owner, site + "/expenses", expense("1", "2026-10-01", " ", "OTHER")).andExpect(status().isBadRequest());
        api.post(owner, site + "/expenses", expense("1", "2026-10-01", "x", "FOOD")).andExpect(status().isBadRequest());
        api.post(owner, site + "/expenses", "{\"amount\":1,\"description\":\"x\",\"category\":\"OTHER\"}")
                .andExpect(status().isBadRequest());
        api.get(owner, site + "/expenses").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void financeManagesExpensesAndAdministratorOnlyReadsThem() throws Exception {
        Session finance = as("FINANCE");
        String id = Api.id(api.post(finance, site + "/expenses", expense("10", "2026-10-01", "Finance", "OTHER"))
                .andExpect(status().isCreated()));
        api.patch(finance, site + "/expenses/" + id, "{\"description\":\"Edited\"}").andExpect(status().isOk());
        api.get(finance, site + "/expenses").andExpect(status().isOk());
        api.get(finance, site + "/budget").andExpect(status().isOk());

        Session admin = as("ADMINISTRATOR");
        api.get(admin, site + "/expenses").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.get(admin, site + "/budget").andExpect(status().isOk());
        api.post(admin, site + "/expenses", expense("10", "2026-10-01", "x", "OTHER")).andExpect(status().isForbidden());
        api.patch(admin, site + "/expenses/" + id, "{\"description\":\"x\"}").andExpect(status().isForbidden());
        api.delete(admin, site + "/expenses/" + id).andExpect(status().isForbidden());

        api.delete(finance, site + "/expenses/" + id).andExpect(status().isNoContent());
    }

    @Test
    void memberAndWorkerCannotSeeOrRecordExpenses() throws Exception {
        String id = Api.id(api.post(owner, site + "/expenses", expense("10", "2026-10-01", "Owner's", "OTHER")));
        for (String role : new String[] {"MEMBER", "WORKER"}) {
            Session s = as(role);
            api.get(s, site + "/expenses").andExpect(status().isForbidden());
            api.get(s, site + "/budget").andExpect(status().isForbidden());
            api.post(s, site + "/expenses", expense("10", "2026-10-01", "x", "OTHER")).andExpect(status().isForbidden());
            api.patch(s, site + "/expenses/" + id, "{\"description\":\"x\"}").andExpect(status().isForbidden());
            api.delete(s, site + "/expenses/" + id).andExpect(status().isForbidden());
        }
        api.get(owner, site + "/expenses").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void otherTenantsAndOtherSitesGetNotFound() throws Exception {
        String id = Api.id(api.post(owner, site + "/expenses", expense("10", "2026-10-01", "x", "OTHER")));
        String otherSite = "/api/organizations/" + orgId + "/projects/"
                + Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"Other\"}"));
        api.patch(owner, otherSite + "/expenses/" + id, "{\"description\":\"x\"}").andExpect(status().isNotFound());
        api.delete(owner, otherSite + "/expenses/" + id).andExpect(status().isNotFound());

        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, site + "/expenses").andExpect(status().isNotFound());
        api.get(stranger, site + "/budget").andExpect(status().isNotFound());
        api.get(stranger, "/api/organizations/" + strangerOrg + "/projects/" + siteId + "/expenses")
                .andExpect(status().isNotFound());
        api.get(owner, "/api/organizations/" + orgId + "/projects/" + UUID.randomUUID() + "/expenses")
                .andExpect(status().isNotFound());
    }
}

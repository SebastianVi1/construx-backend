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

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /api/organizations/{orgId}/finance/summary
 * and GET /api/organizations/{orgId}/finance/expenses/monthly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgFinanceControllerTest {

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
    private String financeBase;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "BuildCo " + UUID.randomUUID());
        financeBase = "/api/organizations/" + orgId + "/finance";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createSite(String name) throws Exception {
        return Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"" + name + "\"}"));
    }

    private void setBudget(String siteId, String amount) throws Exception {
        api.put(owner, "/api/organizations/" + orgId + "/projects/" + siteId + "/budget",
                "{\"allocatedBudget\":" + amount + "}").andExpect(status().isOk());
    }

    private void addExpense(String siteId, String amount, String date, String category) throws Exception {
        api.post(owner, "/api/organizations/" + orgId + "/projects/" + siteId + "/expenses",
                "{\"amount\":" + amount + ",\"spentOn\":\"" + date + "\",\"description\":\"Expense\",\"category\":\""
                        + category + "\"}")
                .andExpect(status().isCreated());
    }

    private void archiveSite(String siteId) throws Exception {
        api.patch(owner, "/api/organizations/" + orgId + "/projects/" + siteId,
                "{\"status\":\"ARCHIVED\"}").andExpect(status().isOk());
    }

    private Session asRole(String role) throws Exception {
        Session s = api.signUp();
        members.add(orgId, s.user(), role);
        return s;
    }

    // ── Summary 200 ───────────────────────────────────────────────────────────

    @Test
    void summaryWithBudgetedOverBudgetNearLimitAndUnbudgetedSites() throws Exception {
        // OK site: 50% spend (50 / 100)
        String okSite = createSite("Alpha");
        setBudget(okSite, "100");
        addExpense(okSite, "50", "2026-01-10", "MATERIALS");

        // OVER_BUDGET site: 110% (110 / 100)
        String overSite = createSite("Beta");
        setBudget(overSite, "100");
        addExpense(overSite, "110", "2026-01-15", "LABOR");

        // NEAR_LIMIT site: 90% (90 / 100)
        String nearSite = createSite("Gamma");
        setBudget(nearSite, "100");
        addExpense(nearSite, "90", "2026-01-20", "EQUIPMENT");

        // NO_BUDGET site: no allocatedBudget
        String noBudgetSite = createSite("Delta");
        addExpense(noBudgetSite, "30", "2026-01-25", "OTHER");

        api.get(owner, financeBase + "/summary")
                .andExpect(status().isOk())
                // Company totals from budgeted sites only (okSite + overSite + nearSite)
                .andExpect(jsonPath("$.totalBudget").value(300.00))
                .andExpect(jsonPath("$.totalSpent").value(250.00))
                .andExpect(jsonPath("$.totalRemaining").value(50.00))
                .andExpect(jsonPath("$.executionPct").value(83.3))
                // Counts
                .andExpect(jsonPath("$.sitesOverBudget").value(1))
                .andExpect(jsonPath("$.sitesNearLimit").value(1))
                // byCategory: sum over budgeted sites only; unbudgeted (OTHER 30) excluded
                .andExpect(jsonPath("$.byCategory", hasSize(4)))
                .andExpect(jsonPath("$.byCategory[0].category").value("MATERIALS"))
                .andExpect(jsonPath("$.byCategory[0].amount").value(50.00))
                .andExpect(jsonPath("$.byCategory[1].category").value("LABOR"))
                .andExpect(jsonPath("$.byCategory[1].amount").value(110.00))
                .andExpect(jsonPath("$.byCategory[2].category").value("EQUIPMENT"))
                .andExpect(jsonPath("$.byCategory[2].amount").value(90.00))
                .andExpect(jsonPath("$.byCategory[3].category").value("OTHER"))
                .andExpect(jsonPath("$.byCategory[3].amount").value(0.00))
                // All 4 included sites, ordered by name
                .andExpect(jsonPath("$.sites", hasSize(4)))
                .andExpect(jsonPath("$.sites[0].siteName").value("Alpha"))
                .andExpect(jsonPath("$.sites[0].status").value("OK"))
                .andExpect(jsonPath("$.sites[0].allocatedBudget").value(100.00))
                .andExpect(jsonPath("$.sites[0].spent").value(50.00))
                .andExpect(jsonPath("$.sites[0].remaining").value(50.00))
                .andExpect(jsonPath("$.sites[0].executionPct").value(50.0))
                .andExpect(jsonPath("$.sites[1].siteName").value("Beta"))
                .andExpect(jsonPath("$.sites[1].status").value("OVER_BUDGET"))
                // NO_BUDGET site: Alpha < Beta < Delta < Gamma alphabetically
                .andExpect(jsonPath("$.sites[2].siteName").value("Delta"))
                .andExpect(jsonPath("$.sites[2].status").value("NO_BUDGET"))
                .andExpect(jsonPath("$.sites[2].allocatedBudget").isEmpty())
                .andExpect(jsonPath("$.sites[2].remaining").isEmpty())
                .andExpect(jsonPath("$.sites[2].executionPct").isEmpty())
                .andExpect(jsonPath("$.sites[2].spent").value(30.00))
                .andExpect(jsonPath("$.sites[3].siteName").value("Gamma"))
                .andExpect(jsonPath("$.sites[3].status").value("NEAR_LIMIT"));
    }

    @Test
    void byCategoryTotalEqualsTotalSpent() throws Exception {
        String siteA = createSite("SiteA");
        setBudget(siteA, "1000");
        addExpense(siteA, "200", "2026-02-01", "MATERIALS");
        addExpense(siteA, "300", "2026-02-02", "LABOR");
        addExpense(siteA, "100", "2026-02-03", "EQUIPMENT");
        addExpense(siteA, "50",  "2026-02-04", "OTHER");

        api.get(owner, financeBase + "/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(650.00))
                // sum(byCategory) == totalSpent
                .andExpect(jsonPath("$.byCategory[0].amount").value(200.00))
                .andExpect(jsonPath("$.byCategory[1].amount").value(300.00))
                .andExpect(jsonPath("$.byCategory[2].amount").value(100.00))
                .andExpect(jsonPath("$.byCategory[3].amount").value(50.00));
    }

    @Test
    void archivedSiteIsAbsentFromSummary() throws Exception {
        String live = createSite("Live");
        setBudget(live, "500");
        addExpense(live, "100", "2026-03-01", "MATERIALS");

        String archived = createSite("Archived");
        setBudget(archived, "1000");
        addExpense(archived, "400", "2026-03-02", "LABOR");
        archiveSite(archived);

        api.get(owner, financeBase + "/summary")
                .andExpect(status().isOk())
                // Archived site excluded from totals and sites list
                .andExpect(jsonPath("$.totalBudget").value(500.00))
                .andExpect(jsonPath("$.totalSpent").value(100.00))
                .andExpect(jsonPath("$.byCategory[1].amount").value(0.00)) // LABOR from archived not counted
                .andExpect(jsonPath("$.sites", hasSize(1)))
                .andExpect(jsonPath("$.sites[0].siteName").value("Live"));
    }

    @Test
    void emptyOrganizationReturnsZerosAndEmptySites() throws Exception {
        api.get(owner, financeBase + "/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBudget").value(0.00))
                .andExpect(jsonPath("$.totalSpent").value(0.00))
                .andExpect(jsonPath("$.totalRemaining").value(0.00))
                .andExpect(jsonPath("$.executionPct").isEmpty())
                .andExpect(jsonPath("$.sitesOverBudget").value(0))
                .andExpect(jsonPath("$.sitesNearLimit").value(0))
                .andExpect(jsonPath("$.byCategory", hasSize(4)))
                .andExpect(jsonPath("$.byCategory[0].amount").value(0.00))
                .andExpect(jsonPath("$.sites").isEmpty());
    }

    @Test
    void expensesOfAnotherOrganizationNeverAppear() throws Exception {
        // Other org with expenses
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        String otherSite = Api.id(api.post(other,
                "/api/organizations/" + otherOrg + "/projects", "{\"name\":\"OtherSite\"}"));
        api.put(other, "/api/organizations/" + otherOrg + "/projects/" + otherSite + "/budget",
                "{\"allocatedBudget\":9999}").andExpect(status().isOk());
        api.post(other, "/api/organizations/" + otherOrg + "/projects/" + otherSite + "/expenses",
                "{\"amount\":5000,\"spentOn\":\"2026-01-01\",\"description\":\"x\",\"category\":\"MATERIALS\"}")
                .andExpect(status().isCreated());

        // Our org has nothing
        api.get(owner, financeBase + "/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBudget").value(0.00))
                .andExpect(jsonPath("$.totalSpent").value(0.00))
                .andExpect(jsonPath("$.sites").isEmpty());
    }

    // ── 403 and 404 for summary ───────────────────────────────────────────────

    @Test
    void memberAndWorkerGetForbiddenOnSummary() throws Exception {
        for (String role : new String[]{"MEMBER", "WORKER"}) {
            Session s = asRole(role);
            api.get(s, financeBase + "/summary").andExpect(status().isForbidden());
        }
    }

    @Test
    void nonMemberGetsNotFoundOnSummary() throws Exception {
        Session stranger = api.signUp();
        api.get(stranger, financeBase + "/summary").andExpect(status().isNotFound());
    }

    @Test
    void unknownOrganizationGetsNotFoundOnSummary() throws Exception {
        api.get(owner, "/api/organizations/" + UUID.randomUUID() + "/finance/summary")
                .andExpect(status().isNotFound());
    }

    @Test
    void adminAndFinanceCanAccessSummary() throws Exception {
        for (String role : new String[]{"ADMINISTRATOR", "FINANCE"}) {
            Session s = asRole(role);
            api.get(s, financeBase + "/summary").andExpect(status().isOk());
        }
    }

    // ── Monthly 200 ───────────────────────────────────────────────────────────

    @Test
    void monthlyWithExplicitRangeAndGapMonths() throws Exception {
        String site = createSite("MonthSite");
        setBudget(site, "5000");
        addExpense(site, "100", "2026-01-10", "MATERIALS");
        addExpense(site, "200", "2026-03-15", "LABOR");
        // No expense in February — gap month must appear as 0.00

        api.get(owner, financeBase + "/expenses/monthly?from=2026-01-01&to=2026-03-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBudget").value(5000.00))
                .andExpect(jsonPath("$.months", hasSize(3)))
                .andExpect(jsonPath("$.months[0].month").value("2026-01"))
                .andExpect(jsonPath("$.months[0].spent").value(100.00))
                .andExpect(jsonPath("$.months[1].month").value("2026-02"))
                .andExpect(jsonPath("$.months[1].spent").value(0.00))
                .andExpect(jsonPath("$.months[2].month").value("2026-03"))
                .andExpect(jsonPath("$.months[2].spent").value(200.00));
    }

    @Test
    void monthlyRangeBoundsAreInclusive() throws Exception {
        String site = createSite("BoundSite");
        setBudget(site, "1000");
        addExpense(site, "50", "2026-02-01", "MATERIALS"); // first day of range
        addExpense(site, "75", "2026-02-28", "LABOR");     // last day of range

        api.get(owner, financeBase + "/expenses/monthly?from=2026-02-01&to=2026-02-28")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months", hasSize(1)))
                .andExpect(jsonPath("$.months[0].month").value("2026-02"))
                .andExpect(jsonPath("$.months[0].spent").value(125.00));
    }

    @Test
    void monthlyDefaultsToCurrentYear() throws Exception {
        String site = createSite("DefaultSite");
        setBudget(site, "1000");

        // Just assert the first month is January of the current year (no crash, no bad range)
        int currentYear = LocalDate.now().getYear();
        api.get(owner, financeBase + "/expenses/monthly")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].month").value(currentYear + "-01"));
    }

    @Test
    void monthlyUnbudgetedSiteExpensesAreExcluded() throws Exception {
        String budgeted = createSite("Budgeted");
        setBudget(budgeted, "500");
        addExpense(budgeted, "100", "2026-04-05", "MATERIALS");

        String unbudgeted = createSite("Unbudgeted");
        addExpense(unbudgeted, "999", "2026-04-05", "LABOR");

        api.get(owner, financeBase + "/expenses/monthly?from=2026-04-01&to=2026-04-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].spent").value(100.00)); // 999 not included
    }

    // ── 400 for monthly ───────────────────────────────────────────────────────

    @Test
    void monthlyFromAfterToGivesBadRequest() throws Exception {
        api.get(owner, financeBase + "/expenses/monthly?from=2026-06-01&to=2026-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void monthlyRangeExceeding36MonthsGivesBadRequest() throws Exception {
        api.get(owner, financeBase + "/expenses/monthly?from=2023-01-01&to=2026-02-28")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void monthlyMalformedDateGivesBadRequest() throws Exception {
        api.get(owner, financeBase + "/expenses/monthly?from=not-a-date")
                .andExpect(status().isBadRequest());
    }

    // ── 403 and 404 for monthly ───────────────────────────────────────────────

    @Test
    void memberAndWorkerGetForbiddenOnMonthly() throws Exception {
        for (String role : new String[]{"MEMBER", "WORKER"}) {
            Session s = asRole(role);
            api.get(s, financeBase + "/expenses/monthly").andExpect(status().isForbidden());
        }
    }

    @Test
    void nonMemberGetsNotFoundOnMonthly() throws Exception {
        Session stranger = api.signUp();
        api.get(stranger, financeBase + "/expenses/monthly").andExpect(status().isNotFound());
    }

    @Test
    void unknownOrganizationGetsNotFoundOnMonthly() throws Exception {
        api.get(owner, "/api/organizations/" + UUID.randomUUID() + "/finance/expenses/monthly")
                .andExpect(status().isNotFound());
    }

    // ── 400 and 404 ordering (validation after auth) ──────────────────────────

    @Test
    void nonMemberWithBadRangeGetsNotFoundNotBadRequest() throws Exception {
        Session stranger = api.signUp();
        // Even with invalid range params, non-member sees 404 (auth check runs first)
        api.get(stranger, financeBase + "/expenses/monthly?from=2026-06-01&to=2026-01-01")
                .andExpect(status().isNotFound());
    }

    @Test
    void memberWithoutPermissionWithBadRangeGetsForbiddenNotBadRequest() throws Exception {
        Session member = asRole("MEMBER");
        api.get(member, financeBase + "/expenses/monthly?from=2026-06-01&to=2026-01-01")
                .andExpect(status().isForbidden());
    }
}

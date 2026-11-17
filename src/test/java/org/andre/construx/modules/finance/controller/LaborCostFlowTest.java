package org.andre.construx.modules.finance.controller;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.attendance.AttendanceTestBase;
import org.andre.construx.modules.identity.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Accrued labor cost of a Site (docs/pricing.md §6.2-§6.4): one day at the rate in force at the end of each day a
 * person has a Check-in, computed on every read. Days are 10 and 11 March 2026 in America/Mexico_City (UTC-6).
 */
class LaborCostFlowTest extends AttendanceTestBase {
    private static final String NEXT_MORNING = "2026-03-11T15:00:00Z";

    @Autowired JdbcTemplate jdbc;

    private UUID peon;
    private String laborCost;

    @BeforeEach
    void seedCatalog() {
        // Peon cost 600 since 1 March and 650 from the morning of 11 March; current cost 650.
        peon = labor("MO011", "PEON", "650.00");
        cost(peon, "600.00", "2026-03-01T15:00:00Z");
        cost(peon, "650.00", "2026-03-11T14:00:00Z");
        laborCost = site + "/labor-cost";
    }

    private UUID labor(String code, String name, String currentCost) {
        UUID unit = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, 'Jornada', 'SEED')",
                unit, orgId, "JOR-" + code);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse(MORNING));
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, current_cost, created_at, updated_at) "
                        + "values (?, ?, ?, 'LABOR', ?, ?, false, false, false, false, 'MANUAL', ?, ?, ?)",
                id, orgId, code, name, unit, new BigDecimal(currentCost), now, now);
        return id;
    }

    private void cost(UUID resource, String amount, String effectiveFrom) {
        jdbc.update("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, source) "
                + "values (?, ?, ?, ?, ?, 'MANUAL')", UUID.randomUUID(), orgId, resource, new BigDecimal(amount),
                Timestamp.from(Instant.parse(effectiveFrom)));
    }

    private String rateOf(UUID membership) throws Exception {
        return rateOf(site, membership);
    }

    private String rateOf(String site, UUID membership) throws Exception {
        String body = api.get(owner, site + "/access").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        List<String> ids = JsonPath.read(body, "$[?(@.membershipId == '" + membership + "')].accessId");
        return site + "/access/" + ids.getFirst() + "/labor-rate";
    }

    private void catalogRate(UUID membership, UUID category) throws Exception {
        api.put(owner, rateOf(membership), "{\"kind\":\"CATALOG\",\"laborCategoryId\":\"" + category + "\"}")
                .andExpect(status().isOk());
    }

    /**
     * Ana (catalog Peon) checks in on the 10th and twice on the 11th; the other worker (no rate) on the 10th.
     * The clock ends on the 11th.
     */
    private void twoDaysOfWork() throws Exception {
        catalogRate(workerMembership, peon);
        checkIn(worker).andExpect(status().isCreated());
        checkIn(otherWorker).andExpect(status().isCreated());
        clock.set(NEXT_MORNING);
        checkIn(worker).andExpect(status().isCreated());
        // A second Check-in the same day (as a move to another zone would leave) is still one jornada.
        jdbc.update("insert into check_in (id, project_id, membership_id, check_in_day, checked_in_at, "
                        + "recorded_by_membership_id) values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), siteId,
                workerMembership, java.sql.Date.valueOf("2026-03-11"),
                Timestamp.from(Instant.parse("2026-03-11T18:00:00Z")), workerMembership);
    }

    @Test
    void theSiteAccruesOneDayPerPersonAndDayAtTheRateOfThatDay() throws Exception {
        twoDaysOfWork();

        api.get(owner, laborCost).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-10"))
                .andExpect(jsonPath("$.to").value("2026-03-11"))
                .andExpect(jsonPath("$.total").value("1250.00"))
                .andExpect(jsonPath("$.personDays").value(3))
                .andExpect(jsonPath("$.unratedPersonDays").value(1))
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(jsonPath("$.people[0].membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.people[0].fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.people[0].days").value(2))
                .andExpect(jsonPath("$.people[0].amount").value("1250.00"))
                .andExpect(jsonPath("$.people[0].unratedDays").value(0))
                .andExpect(jsonPath("$.people[0].currentRate.kind").value("CATALOG"))
                .andExpect(jsonPath("$.people[0].currentRate.laborCategory.id").value(peon.toString()))
                .andExpect(jsonPath("$.people[0].currentRate.laborCategory.code").value("MO011"))
                .andExpect(jsonPath("$.people[0].currentRate.laborCategory.name").value("PEON"))
                .andExpect(jsonPath("$.people[0].currentRate.dailyAmount").value("650.00"))
                .andExpect(jsonPath("$.people[1].membershipId").value(otherWorkerMembership.toString()))
                .andExpect(jsonPath("$.people[1].fullName").value(otherWorker.user().getEmail()))
                .andExpect(jsonPath("$.people[1].days").value(1))
                .andExpect(jsonPath("$.people[1].amount").value("0.00"))
                .andExpect(jsonPath("$.people[1].unratedDays").value(1))
                .andExpect(jsonPath("$.people[1].currentRate").isEmpty());

        api.get(owner, laborCost + "?from=2026-03-10&to=2026-03-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("600.00"))
                .andExpect(jsonPath("$.personDays").value(2));
    }

    @Test
    void theDrillDownListsEveryPersonDayNewestFirstWithItsRateAndUnratedReason() throws Exception {
        profiles.save(new UserProfile(otherWorker.user(), "Zoe Ruiz"));
        twoDaysOfWork();

        api.get(owner, laborCost + "/days").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].day").value("2026-03-11"))
                .andExpect(jsonPath("$.content[0].fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.content[0].amount").value("650.00"))
                .andExpect(jsonPath("$.content[0].rate.kind").value("CATALOG"))
                .andExpect(jsonPath("$.content[0].rate.laborCategory.code").value("MO011"))
                .andExpect(jsonPath("$.content[0].rate.dailyAmount").value("650.00"))
                .andExpect(jsonPath("$.content[0].unrated").value(false))
                .andExpect(jsonPath("$.content[0].unratedReason").isEmpty())
                .andExpect(jsonPath("$.content[1].day").value("2026-03-10"))
                .andExpect(jsonPath("$.content[1].membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.content[1].amount").value("600.00"))
                .andExpect(jsonPath("$.content[1].rate.dailyAmount").value("600.00"))
                .andExpect(jsonPath("$.content[2].day").value("2026-03-10"))
                .andExpect(jsonPath("$.content[2].membershipId").value(otherWorkerMembership.toString()))
                .andExpect(jsonPath("$.content[2].fullName").value("Zoe Ruiz"))
                .andExpect(jsonPath("$.content[2].rate").isEmpty())
                .andExpect(jsonPath("$.content[2].amount").value("0.00"))
                .andExpect(jsonPath("$.content[2].unrated").value(true))
                .andExpect(jsonPath("$.content[2].unratedReason").value("NO_RATE"));

        api.get(owner, laborCost + "/days?membershipId=" + workerMembership + "&size=1&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].day").value("2026-03-10"));
    }

    @Test
    void aCatalogRateWhoseCategoryHadNoCostThatDayIsFlaggedNoCostYet() throws Exception {
        UUID oficial = labor("MO020", "OFICIAL", "800.00");
        cost(oficial, "800.00", NEXT_MORNING);
        catalogRate(workerMembership, oficial);
        checkIn(worker).andExpect(status().isCreated());

        api.get(owner, laborCost + "/days").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value("0.00"))
                .andExpect(jsonPath("$.content[0].rate.kind").value("CATALOG"))
                .andExpect(jsonPath("$.content[0].rate.dailyAmount").isEmpty())
                .andExpect(jsonPath("$.content[0].unratedReason").value("NO_COST_YET"));
    }

    @Test
    void aRemovedCheckInAccruesNothing() throws Exception {
        catalogRate(workerMembership, peon);
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        api.delete(worker, checkIns + "/" + id).andExpect(status().isNoContent());

        api.get(owner, laborCost).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.personDays").value(0))
                .andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void withoutCheckInsTheRangeDefaultsToToday() throws Exception {
        api.get(owner, laborCost).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-10"))
                .andExpect(jsonPath("$.to").value("2026-03-10"))
                .andExpect(jsonPath("$.total").value("0.00"));
    }

    @Test
    void anInvertedOrTooLongRangeIsRefused() throws Exception {
        api.get(owner, laborCost + "?from=2026-03-10&to=2026-03-09").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, laborCost + "?from=2025-03-09&to=2026-03-10").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, laborCost + "?from=2025-03-10&to=2026-03-10").andExpect(status().isOk());
        api.get(owner, laborCost + "/days?from=2026-03-10&to=2026-03-09").andExpect(status().isBadRequest());
        api.get(owner, laborCost + "/days?size=201").andExpect(status().isBadRequest());
    }

    @Test
    void onlyBudgetRolesReadTheAccrual() throws Exception {
        api.get(worker, laborCost).andExpect(status().isForbidden());
        api.get(worker, laborCost + "/days").andExpect(status().isForbidden());
        var stranger = api.signUp();
        api.get(stranger, laborCost).andExpect(status().isNotFound());
    }

    @Test
    void theSiteBudgetCountsTheAccrualBesideRecordedExpenses() throws Exception {
        twoDaysOfWork();
        api.put(owner, site + "/budget", "{\"allocatedBudget\":2000}").andExpect(status().isOk());
        api.post(owner, site + "/expenses", "{\"amount\":100,\"spentOn\":\"2026-03-11\",\"description\":\"Bonus\","
                + "\"category\":\"LABOR\"}").andExpect(status().isCreated());
        api.post(owner, site + "/expenses", "{\"amount\":50,\"spentOn\":\"2026-03-11\",\"description\":\"Sand\","
                + "\"category\":\"MATERIALS\"}").andExpect(status().isCreated());

        api.get(owner, site + "/budget").andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedExpenses").value(150))
                .andExpect(jsonPath("$.laborAccrued").value(1250))
                .andExpect(jsonPath("$.spent").value(1400))
                .andExpect(jsonPath("$.remaining").value(600))
                .andExpect(jsonPath("$.byCategory[?(@.category == 'LABOR')].amount").value(1350.0))
                .andExpect(jsonPath("$.byCategory[?(@.category == 'MATERIALS')].amount").value(50.0));
    }

    @Test
    void organizationFinanceIncludesTheAccrualOfBudgetedSites() throws Exception {
        twoDaysOfWork();
        api.put(owner, site + "/budget", "{\"allocatedBudget\":1000}").andExpect(status().isOk());
        UUID unbudgeted = createSite("Annex");
        members.grantSite(unbudgeted, workerMembership);
        api.put(owner, rateOf(sitePath(unbudgeted), workerMembership), "{\"kind\":\"FIXED\",\"dailyAmount\":\"500\"}")
                .andExpect(status().isOk());
        api.post(worker, sitePath(unbudgeted) + "/attendance/check-ins", "{}").andExpect(status().isCreated());

        api.get(owner, org + "/finance/summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(1250))
                .andExpect(jsonPath("$.totalRemaining").value(-250))
                .andExpect(jsonPath("$.sitesOverBudget").value(1))
                .andExpect(jsonPath("$.byCategory[?(@.category == 'LABOR')].amount").value(1250.0))
                .andExpect(jsonPath("$.sites[?(@.siteName == 'Tower')].spent").value(1250.0))
                .andExpect(jsonPath("$.sites[?(@.siteName == 'Tower')].status").value("OVER_BUDGET"))
                .andExpect(jsonPath("$.sites[?(@.siteName == 'Annex')].spent").value(500.0));

        api.get(owner, org + "/finance/expenses/monthly?from=2026-02-01&to=2026-03-31").andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].spent").value(0))
                .andExpect(jsonPath("$.months[1].month").value("2026-03"))
                .andExpect(jsonPath("$.months[1].spent").value(1250));
    }
}

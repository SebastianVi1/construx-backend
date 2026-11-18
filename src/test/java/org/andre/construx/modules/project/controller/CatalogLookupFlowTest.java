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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Site's catalog lookup (docs/pricing.md §6.7): codes and names of Resources, never costs, for whoever sets a
 * Labor rate or records an expense on the Site without being able to read the Catalog.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogLookupFlowTest {
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
    private Session owner;
    private UUID orgId, siteId, unitId;
    private String site;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        siteId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        site = "/api/organizations/" + orgId + "/projects/" + siteId;
        unitId = unit(orgId, "JOR");
    }

    private UUID unit(UUID organizationId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, ?, 'SEED')",
                id, organizationId, code, code);
        return id;
    }

    private UUID resource(UUID organizationId, UUID unit, String code, String type, String name, String cost,
                          boolean composite, boolean archived) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, current_cost, cost_updated_at, archived_at, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, false, ?, false, false, 'MANUAL', ?, ?, ?, ?, ?)",
                id, organizationId, code, type, name, unit, composite, cost == null ? null : new BigDecimal(cost),
                cost == null ? null : now, archived ? now : null, now, now);
        return id;
    }

    private UUID resource(String code, String type, String name, String cost) {
        return resource(orgId, unitId, code, type, name, cost, false, false);
    }

    /** One of every kind of Resource the two lookups must tell apart. */
    private void catalog() {
        resource("MO011", "LABOR", "Peon", "610.37");
        resource("MO012", "LABOR", "Albanil sin costo", null);
        resource(orgId, unitId, "CU-01", "LABOR", "Cuadrilla", "999.11", true, false);
        resource(orgId, unitId, "MO-OLD", "LABOR", "Retirado", "500.00", false, true);
        resource("MAT-CEM", "MATERIAL", "Cemento gris", "245.53");
        resource("MAT-NEW", "MATERIAL", "Sin costo", null);
        resource("MAT-FREE", "MATERIAL", "Gratis", "0.00");
        resource("EQ-MIX", "EQUIPMENT", "Revolvedora", "85.33");
        resource("WI-1", "WORK_ITEM", "Firme", "120.00");
        resource(unit(otherOrg(), "PZA"), "MO099", "LABOR", "Ajeno", "700.00");
    }

    private UUID resource(UUID foreignUnit, String code, String type, String name, String cost) {
        UUID organizationId = jdbc.queryForObject("select organization_id from unit where id = ?", UUID.class,
                foreignUnit);
        return resource(organizationId, foreignUnit, code, type, name, cost, false, false);
    }

    private UUID otherOrg() {
        try {
            return api.createOrganization(api.signUp(), "Other " + UUID.randomUUID());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String lookup(String purpose) {
        return site + "/catalog-lookup?purpose=" + purpose;
    }

    private Session member(String role) throws Exception {
        Session session = api.signUp();
        members.grantSite(siteId, members.add(orgId, session.user(), role));
        return session;
    }

    @Test
    void aSiteManagerListsTheSimpleActiveLaborCategoriesByCodeAndNameWithoutCosts() throws Exception {
        catalog();
        Session manager = member("SITE_MANAGER");

        api.get(manager, lookup("LABOR_RATE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[*].code").value(contains("MO011", "MO012")))
                .andExpect(jsonPath("$.content[0].name").value("Peon"))
                .andExpect(jsonPath("$.content[0].type").value("LABOR"))
                .andExpect(jsonPath("$.content[0].unitCode").value("JOR"))
                .andExpect(jsonPath("$.content[0].id").isNotEmpty())
                .andExpect(jsonPath("$.content[0].keys()").value(
                        containsInAnyOrder("id", "code", "name", "type", "unitCode")))
                .andExpect(content().string(not(containsString("610"))));
    }

    @Test
    void theQueryNarrowsByCodeOrNameAndPagesFollowTheCatalogShape() throws Exception {
        catalog();

        api.get(owner, lookup("LABOR_RATE") + "&query=PEON").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(contains("MO011")));
        api.get(owner, lookup("LABOR_RATE") + "&query=mo012").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(contains("MO012")));
        api.get(owner, lookup("LABOR_RATE") + "&page=1&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(contains("MO012")))
                .andExpect(jsonPath("$.totalPages").value(2));
        api.get(owner, lookup("LABOR_RATE") + "&size=201").andExpect(status().isBadRequest());
        api.get(owner, site + "/catalog-lookup").andExpect(status().isBadRequest());
        api.get(owner, lookup("WORK_ITEM")).andExpect(status().isBadRequest());
    }

    @Test
    void theExpenseLookupListsSimpleActiveChargeableResourcesThatHaveACost() throws Exception {
        catalog();

        api.get(owner, lookup("EXPENSE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(contains("EQ-MIX", "MAT-CEM", "MO011")))
                .andExpect(content().string(not(containsString("245"))))
                .andExpect(content().string(not(containsString("85.33"))));
    }

    /** A task on the site with {@code leadAccess} as its Lead. */
    private void taskLedBy(UUID leadAccess) throws Exception {
        String goal = site + "/goals/" + Api.id(api.post(owner, site + "/goals",
                "{\"name\":\"Structure\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        api.post(owner, goal + "/activities", "{\"name\":\"Pour slab\",\"startAt\":\"2027-01-02T00:00:00Z\","
                + "\"dueAt\":\"2027-01-10T00:00:00Z\",\"assignees\":[{\"accessId\":\"" + leadAccess
                + "\",\"taskRole\":\"LEAD\"}]}").andExpect(status().isCreated());
    }

    @Test
    void aTaskLeadWithoutViewPricesMayLookUpExpensesButNotLaborRates() throws Exception {
        catalog();
        Session lead = api.signUp();
        taskLedBy(members.grantSite(siteId, members.add(orgId, lead.user(), "WORKER")));

        api.get(lead, lookup("EXPENSE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code").value(contains("EQ-MIX", "MAT-CEM", "MO011")))
                .andExpect(content().string(not(containsString("245"))));
        api.get(lead, lookup("LABOR_RATE")).andExpect(status().isForbidden());
    }

    @Test
    void workersResidentsAndFinanceWithoutTheRightPermissionAre403() throws Exception {
        Session worker = member("WORKER");
        Session resident = member("RESIDENT");
        Session manager = member("SITE_MANAGER");
        Session finance = member("FINANCE");

        for (Session s : new Session[] {worker, resident}) {
            api.get(s, lookup("LABOR_RATE")).andExpect(status().isForbidden());
            api.get(s, lookup("EXPENSE")).andExpect(status().isForbidden());
        }
        api.get(manager, lookup("EXPENSE")).andExpect(status().isForbidden());
        api.get(finance, lookup("LABOR_RATE")).andExpect(status().isForbidden());
        api.get(finance, lookup("EXPENSE")).andExpect(status().isOk());
    }

    @Test
    void anotherTenantOrAnUnknownSiteIs404() throws Exception {
        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());

        api.get(stranger, lookup("LABOR_RATE")).andExpect(status().isNotFound());
        api.get(stranger, "/api/organizations/" + strangerOrg + "/projects/" + siteId
                + "/catalog-lookup?purpose=EXPENSE").andExpect(status().isNotFound());
        api.get(owner, "/api/organizations/" + orgId + "/projects/" + UUID.randomUUID()
                + "/catalog-lookup?purpose=EXPENSE").andExpect(status().isNotFound());
    }
}

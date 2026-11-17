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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Expenses recorded from the Catalog: a Resource and a quantity, the unit cost frozen (docs/pricing.md §6.5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogExpenseFlowTest {
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
    private Session owner;
    private UUID orgId, unitId;
    private String site;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        String base = "/api/organizations/" + orgId + "/projects";
        site = base + "/" + Api.id(api.post(owner, base, "{\"name\":\"Tower\"}"));
        api.put(owner, site + "/budget", "{\"allocatedBudget\":100000}").andExpect(status().isOk());
        unitId = unit(orgId, "SACO");
    }

    private UUID unit(UUID organizationId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, ?, 'SEED')",
                id, organizationId, code, code);
        return id;
    }

    /** A simple Resource of {@code organizationId}; {@code cost} null leaves it without cost. */
    private UUID resource(UUID organizationId, UUID unit, String code, String type, String name, String cost) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, current_cost, cost_updated_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, false, false, false, false, 'MANUAL', ?, ?, ?, ?)",
                id, organizationId, code, type, name, unit, cost == null ? null : new BigDecimal(cost),
                cost == null ? null : now, now, now);
        return id;
    }

    private static String fromCatalog(UUID resourceId, String quantity, String extra) {
        return "{\"resourceId\":\"" + resourceId + "\",\"quantity\":" + quantity + ",\"spentOn\":\"2027-01-03\""
                + extra + "}";
    }

    @Test
    void anExpenseFromTheCatalogComputesItsAmountCategoryAndDescription() throws Exception {
        UUID cement = resource(orgId, unitId, "MAN-CEM-001", "MATERIAL", "Cemento gris 50 kg", "245.50");

        api.post(owner, site + "/expenses", fromCatalog(cement, "\"12.5\"", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3068.75))
                .andExpect(jsonPath("$.category").value("MATERIALS"))
                .andExpect(jsonPath("$.description").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.quantity").value("12.5"))
                .andExpect(jsonPath("$.unitCost").value("245.50"))
                .andExpect(jsonPath("$.resource.id").value(cement.toString()))
                .andExpect(jsonPath("$.resource.code").value("MAN-CEM-001"))
                .andExpect(jsonPath("$.resource.name").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.resource.type").value("MATERIAL"))
                .andExpect(jsonPath("$.resource.unitCode").value("SACO"));
        api.get(owner, site + "/budget").andExpect(jsonPath("$.spent").value(3068.75));
    }

    @Test
    void equipmentAndLaborDeduceTheirCategoryAndATypedDescriptionWins() throws Exception {
        UUID mixer = resource(orgId, unitId, "EQ-MIX", "EQUIPMENT", "Revolvedora 1 saco", "85.333");
        UUID mason = resource(orgId, unitId, "MO-ALB", "LABOR", "Albañil", "612.40");
        set(mason, "is_composite", true);

        api.post(owner, site + "/expenses", fromCatalog(mixer, "\"3\"", ",\"description\":\"Mixer, three hours\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("EQUIPMENT"))
                .andExpect(jsonPath("$.description").value("Mixer, three hours"));
        api.post(owner, site + "/expenses", fromCatalog(mason, "\"0.333333\"", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("LABOR"))
                .andExpect(jsonPath("$.amount").value(204.13));
    }

    @Test
    void theUnitCostIsFrozenWhenTheCatalogChanges() throws Exception {
        UUID cement = resource(orgId, unitId, "MAN-CEM-001", "MATERIAL", "Cemento gris 50 kg", "245.50");
        api.post(owner, site + "/expenses", fromCatalog(cement, "2", "")).andExpect(status().isCreated());

        set(cement, "current_cost", new BigDecimal("300.00"));
        set(cement, "name", "Cemento gris CPC 30R");

        api.get(owner, site + "/expenses").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(491.00))
                .andExpect(jsonPath("$[0].unitCost").value("245.50"))
                .andExpect(jsonPath("$[0].description").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$[0].resource.name").value("Cemento gris CPC 30R"));
        api.post(owner, site + "/expenses", fromCatalog(cement, "2", ""))
                .andExpect(jsonPath("$.unitCost").value("300.00"));
    }

    @Test
    void handTypedExpensesKeepWorkingAndShowNoResource() throws Exception {
        api.post(owner, site + "/expenses",
                        "{\"amount\":50,\"spentOn\":\"2027-01-03\",\"description\":\"Permit\",\"category\":\"OTHER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resource").isEmpty())
                .andExpect(jsonPath("$.quantity").isEmpty())
                .andExpect(jsonPath("$.unitCost").isEmpty());
        api.post(owner, site + "/expenses", "{\"amount\":50,\"spentOn\":\"2027-01-03\",\"description\":\"Permit\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void catalogFieldsCannotBeMixedWithTypedOnes() throws Exception {
        UUID cement = resource(orgId, unitId, "MAN-CEM-001", "MATERIAL", "Cemento", "245.50");

        api.post(owner, site + "/expenses", fromCatalog(cement, "1", ",\"amount\":10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_EXPENSE_FIELDS"));
        api.post(owner, site + "/expenses", fromCatalog(cement, "1", ",\"category\":\"OTHER\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_EXPENSE_FIELDS"));
        api.post(owner, site + "/expenses", "{\"resourceId\":\"" + cement + "\",\"spentOn\":\"2027-01-03\"}")
                .andExpect(status().isBadRequest());
        for (String bad : new String[] {"0", "-1", "\"0.0000001\""}) {
            api.post(owner, site + "/expenses", fromCatalog(cement, bad, "")).andExpect(status().isBadRequest());
        }
        api.get(owner, site + "/expenses").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void workItemsArchivedAndCostlessResourcesCannotBeCharged() throws Exception {
        UUID workItem = resource(orgId, unitId, "WI-1", "WORK_ITEM", "Firme de concreto", "120.00");
        UUID archived = resource(orgId, unitId, "MAT-OLD", "MATERIAL", "Old", "10.00");
        set(archived, "archived_at", Timestamp.from(Instant.now()));
        UUID costless = resource(orgId, unitId, "MAT-NEW", "MATERIAL", "New", null);
        UUID free = resource(orgId, unitId, "MAT-FREE", "MATERIAL", "Free", "0.00");
        UUID foreign = resource(unit(otherOrg(), "PZA"), "MAT-X", "MATERIAL", "Foreign", "10.00");

        api.post(owner, site + "/expenses", fromCatalog(workItem, "1", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_EXPENSABLE"));
        api.post(owner, site + "/expenses", fromCatalog(archived, "1", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_RESOURCE"));
        api.post(owner, site + "/expenses", fromCatalog(costless, "1", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESOURCE_WITHOUT_COST"));
        api.post(owner, site + "/expenses", fromCatalog(free, "1", "")).andExpect(status().isBadRequest());
        api.post(owner, site + "/expenses", fromCatalog(foreign, "1", "")).andExpect(status().isNotFound());
        api.post(owner, site + "/expenses", fromCatalog(UUID.randomUUID(), "1", "")).andExpect(status().isNotFound());
    }

    @Test
    void aCatalogExpenseOnlyChangesItsDateAndDescription() throws Exception {
        UUID cement = resource(orgId, unitId, "MAN-CEM-001", "MATERIAL", "Cemento", "245.50");
        UUID other = resource(orgId, unitId, "MAN-CEM-002", "MATERIAL", "Cemento blanco", "300.00");
        String expense = site + "/expenses/" + Api.id(api.post(owner, site + "/expenses", fromCatalog(cement, "2", "")));

        for (String locked : new String[] {"{\"amount\":10}", "{\"category\":\"OTHER\"}",
                "{\"resourceId\":\"" + other + "\"}", "{\"quantity\":3}"}) {
            api.patch(owner, expense, locked)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_EXPENSE_LOCKED"));
        }
        api.patch(owner, expense, "{\"spentOn\":\"2027-02-01\",\"description\":\"Two bags\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentOn").value("2027-02-01"))
                .andExpect(jsonPath("$.description").value("Two bags"))
                .andExpect(jsonPath("$.amount").value(491.00))
                .andExpect(jsonPath("$.resource.code").value("MAN-CEM-001"));

        String typed = site + "/expenses/" + Api.id(api.post(owner, site + "/expenses",
                "{\"amount\":50,\"spentOn\":\"2027-01-03\",\"description\":\"Permit\",\"category\":\"OTHER\"}"));
        api.patch(owner, typed, "{\"resourceId\":\"" + other + "\",\"quantity\":1}").andExpect(status().isBadRequest());
        api.patch(owner, typed, "{\"amount\":60}").andExpect(status().isOk());
    }

    @Test
    void theTaskLeadChargesTheCatalogToTheirTaskAndOthersFollowTheExpenseRules() throws Exception {
        Members members = new Members(txm, organizations, memberships, projects, accesses);
        UUID siteId = UUID.fromString(site.substring(site.lastIndexOf('/') + 1));
        Session ana = api.signUp();
        UUID anaAccess = members.grantSite(siteId, members.add(orgId, ana.user(), "WORKER"));
        Session bob = api.signUp();
        members.grantSite(siteId, members.add(orgId, bob.user(), "WORKER"));
        String goal = site + "/goals/" + Api.id(api.post(owner, site + "/goals",
                "{\"name\":\"Structure\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        String task = goal + "/activities/" + Api.id(api.post(owner, goal + "/activities", "{\"name\":\"Pour slab\","
                + "\"startAt\":\"2027-01-02T00:00:00Z\",\"dueAt\":\"2027-01-10T00:00:00Z\",\"assignees\":[{\"accessId\":\""
                + anaAccess + "\",\"taskRole\":\"LEAD\"}]}").andExpect(status().isCreated()));
        UUID cement = resource(orgId, unitId, "MAN-CEM-001", "MATERIAL", "Cemento", "245.50");

        String own = Api.id(api.post(ana, task + "/expenses", fromCatalog(cement, "4", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(982.00))
                .andExpect(jsonPath("$.resource.code").value("MAN-CEM-001")));
        api.get(ana, task + "/expenses").andExpect(jsonPath("$[0].unitCost").value("245.50"));
        api.patch(ana, site + "/expenses/" + own, "{\"description\":\"Cement for the slab\"}")
                .andExpect(status().isOk());
        api.post(ana, site + "/expenses", fromCatalog(cement, "1", "")).andExpect(status().isForbidden());
        api.post(bob, task + "/expenses", fromCatalog(cement, "1", "")).andExpect(status().isForbidden());
        api.post(bob, site + "/expenses", fromCatalog(cement, "1", "")).andExpect(status().isForbidden());
    }

    private UUID resource(UUID unit, String code, String type, String name, String cost) {
        UUID organizationId = jdbc.queryForObject("select organization_id from unit where id = ?", UUID.class, unit);
        return resource(organizationId, unit, code, type, name, cost);
    }

    private void set(UUID resourceId, String column, Object value) {
        jdbc.update("update resource set " + column + " = ? where id = ?", value, resourceId);
    }

    private UUID otherOrg() throws Exception {
        return api.createOrganization(api.signUp(), "Other " + UUID.randomUUID());
    }
}

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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Labor rate of a person on a Site (docs/pricing.md §6.1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LaborRateFlowTest {
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
    private Session owner;
    private UUID orgId;
    private UUID siteId;
    private String site;
    private Session ana;
    private UUID anaMembership;
    private String anaRate;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        profiles.save(new UserProfile(owner.user(), "Olga Owner", "+5215500000001"));
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        siteId = createSite("Tower");
        site = siteUrl(siteId);
        ana = api.signUp();
        profiles.save(new UserProfile(ana.user(), "Ana Perez", "+5215512345678"));
        anaMembership = members.add(orgId, ana.user(), "WORKER");
        anaRate = site + "/access/" + members.grantSite(siteId, anaMembership) + "/labor-rate";
    }

    private UUID createSite(String name) throws Exception {
        return UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"" + name + "\"}")));
    }

    private String siteUrl(UUID projectId) {
        return "/api/organizations/" + orgId + "/projects/" + projectId;
    }

    private UUID resource(UUID organizationId, String code, String type, String cost, boolean composite,
                          boolean archived) {
        UUID unit = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, 'Jornada', 'SEED')",
                unit, organizationId, "JOR-" + code);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, current_cost, archived_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, false, ?, false, false, 'MANUAL', ?, ?, ?, ?)",
                id, organizationId, code, type, "Cat " + code, unit, composite,
                cost == null ? null : new BigDecimal(cost), archived ? now : null, now, now);
        return id;
    }

    private UUID labor(String code, String realWage) {
        return resource(orgId, code, "LABOR", realWage, false, false);
    }

    private static String catalog(UUID categoryId) {
        return "{\"kind\":\"CATALOG\",\"laborCategoryId\":\"" + categoryId + "\"}";
    }

    private static String fixed(String amount) {
        return "{\"kind\":\"FIXED\",\"dailyAmount\":\"" + amount + "\"}";
    }

    private static final String NONE = "{\"kind\":\"NONE\"}";

    @Test
    void aManagerSetsReplacesAndClearsTheRateAndEachChangeIsAHistoryRow() throws Exception {
        UUID peon = labor("MO011", "610.00");

        api.put(owner, anaRate, catalog(peon)).andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(anaMembership.toString()))
                .andExpect(jsonPath("$.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.laborRate.kind").value("CATALOG"))
                .andExpect(jsonPath("$.laborRate.laborCategory.id").value(peon.toString()))
                .andExpect(jsonPath("$.laborRate.laborCategory.code").value("MO011"))
                .andExpect(jsonPath("$.laborRate.laborCategory.name").value("Cat MO011"))
                .andExpect(jsonPath("$.laborRate.laborCategory.realWage").value("610.00"))
                .andExpect(jsonPath("$.laborRate.dailyAmount").value("610.00"))
                .andExpect(jsonPath("$.laborRate.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.laborRate.setBy.fullName").value("Olga Owner"));

        // A catalog rate follows the category's current cost.
        jdbc.update("update resource set current_cost = ? where id = ?", new BigDecimal("640.00"), peon);
        api.get(owner, site + "/access").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].laborRate.kind").value("CATALOG"))
                .andExpect(jsonPath("$[0].laborRate.dailyAmount").value("640.00"));

        api.put(owner, anaRate, catalog(peon)).andExpect(status().isOk());
        api.put(owner, anaRate, fixed("650.5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.laborRate.kind").value("FIXED"))
                .andExpect(jsonPath("$.laborRate.laborCategory").isEmpty())
                .andExpect(jsonPath("$.laborRate.dailyAmount").value("650.50"));
        api.put(owner, anaRate, fixed("650.50")).andExpect(status().isOk());
        api.put(owner, anaRate, NONE).andExpect(status().isOk())
                .andExpect(jsonPath("$.laborRate.kind").value("NONE"))
                .andExpect(jsonPath("$.laborRate.dailyAmount").isEmpty());
        api.put(owner, anaRate, NONE).andExpect(status().isOk());

        api.get(owner, anaRate).andExpect(status().isOk())
                .andExpect(jsonPath("$.current.kind").value("NONE"))
                .andExpect(jsonPath("$.suggested").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].kind").value("NONE"))
                .andExpect(jsonPath("$.history[1].kind").value("FIXED"))
                .andExpect(jsonPath("$.history[2].kind").value("CATALOG"))
                .andExpect(jsonPath("$.history[2].dailyAmount").value("640.00"))
                .andExpect(jsonPath("$.history[2].setBy.membershipId")
                        .value(members.ownerMembership(orgId, owner.user()).toString()));
    }

    @Test
    void withoutARateTheAccessShowsNullAndTheReadIsEmpty() throws Exception {
        api.get(owner, site + "/access").andExpect(jsonPath("$[0].laborRate").isEmpty());
        api.get(owner, anaRate).andExpect(status().isOk())
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.suggested").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void theSuggestionIsTheLatestNonNoneRateOnAnotherSite() throws Exception {
        UUID peon = labor("MO011", "610.00");
        UUID otherSite = createSite("Bridge");
        UUID thirdSite = createSite("Road");
        String other = siteUrl(otherSite) + "/access/" + members.grantSite(otherSite, anaMembership) + "/labor-rate";
        String third = siteUrl(thirdSite) + "/access/" + members.grantSite(thirdSite, anaMembership) + "/labor-rate";

        api.put(owner, other, catalog(peon)).andExpect(status().isOk());
        api.put(owner, third, fixed("700.00")).andExpect(status().isOk());
        api.put(owner, third, NONE).andExpect(status().isOk());
        api.put(owner, anaRate, fixed("1.00")).andExpect(status().isOk());

        api.get(owner, anaRate).andExpect(status().isOk())
                .andExpect(jsonPath("$.current.kind").value("FIXED"))
                .andExpect(jsonPath("$.suggested.kind").value("FIXED"))
                .andExpect(jsonPath("$.suggested.dailyAmount").value("700.00"))
                .andExpect(jsonPath("$.suggested.fromProject.id").value(thirdSite.toString()))
                .andExpect(jsonPath("$.suggested.fromProject.name").value("Road"));
    }

    @Test
    void theRateIsVisibleOnlyToWhoeverMaySetItOrViewsBudgets() throws Exception {
        UUID peon = labor("MO011", "610.00");
        api.put(owner, anaRate, catalog(peon)).andExpect(status().isOk());

        Session finance = api.signUp();
        members.grantSite(siteId, members.add(orgId, finance.user(), "FINANCE"));
        Session manager = api.signUp();
        members.grantSite(siteId, members.add(orgId, manager.user(), "SITE_MANAGER"));
        Session resident = api.signUp();
        members.grantSite(siteId, members.add(orgId, resident.user(), "RESIDENT"));

        for (Session reader : new Session[] {owner, finance, manager}) {
            api.get(reader, site + "/access").andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.membershipId=='" + anaMembership + "')].laborRate.kind")
                            .value("CATALOG"));
            api.get(reader, anaRate).andExpect(status().isOk()).andExpect(jsonPath("$.current.kind").value("CATALOG"));
        }
        for (Session outsider : new Session[] {ana, resident}) {
            api.get(outsider, site + "/access").andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.membershipId=='" + anaMembership + "')].laborRate").value(
                            org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
            api.get(outsider, anaRate).andExpect(status().isForbidden());
            api.put(outsider, anaRate, NONE).andExpect(status().isForbidden());
        }
        api.put(finance, anaRate, NONE).andExpect(status().isForbidden());
        api.put(manager, anaRate, fixed("620.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.laborRate.kind").value("FIXED"));
    }

    @Test
    void withoutManageProjectTheAnswerIs403BeforeTheCategoryIsLookedUp() throws Exception {
        Session resident = api.signUp();
        members.grantSite(siteId, members.add(orgId, resident.user(), "RESIDENT"));
        UUID foreign = resource(api.createOrganization(api.signUp(), "Other " + UUID.randomUUID()),
                "MO011", "LABOR", "610.00", false, false);
        UUID material = resource(orgId, "MAT-1", "MATERIAL", "10.00", false, false);

        api.put(resident, anaRate, catalog(UUID.randomUUID())).andExpect(status().isForbidden());
        api.put(resident, anaRate, catalog(foreign)).andExpect(status().isForbidden());
        api.put(resident, anaRate, catalog(material)).andExpect(status().isForbidden());
        api.put(resident, site + "/access/" + UUID.randomUUID() + "/labor-rate", NONE)
                .andExpect(status().isNotFound());
    }

    @Test
    void theCategoryMustBeASimpleActiveLaborResourceOfTheOrganization() throws Exception {
        UUID foreign = resource(api.createOrganization(api.signUp(), "Other " + UUID.randomUUID()),
                "MO011", "LABOR", "610.00", false, false);
        UUID material = resource(orgId, "MAT-1", "MATERIAL", "10.00", false, false);
        UUID gang = resource(orgId, "CUAD-1", "LABOR", "900.00", true, false);
        UUID archived = resource(orgId, "MO099", "LABOR", "610.00", false, true);

        api.put(owner, anaRate, catalog(foreign)).andExpect(status().isNotFound());
        api.put(owner, anaRate, catalog(UUID.randomUUID())).andExpect(status().isNotFound());
        api.put(owner, anaRate, catalog(material)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.put(owner, anaRate, catalog(gang)).andExpect(status().isBadRequest());
        api.put(owner, anaRate, catalog(archived)).andExpect(status().isBadRequest());
        api.put(owner, anaRate, "{\"kind\":\"CATALOG\"}").andExpect(status().isBadRequest());
        api.put(owner, anaRate, fixed("-1.00")).andExpect(status().isBadRequest());
        api.put(owner, anaRate, fixed("1.005")).andExpect(status().isBadRequest());
        api.put(owner, anaRate, "{\"kind\":\"FIXED\"}").andExpect(status().isBadRequest());
        api.put(owner, anaRate, "{\"kind\":\"OTHER\"}").andExpect(status().isBadRequest());
        api.put(owner, anaRate, "{}").andExpect(status().isBadRequest());
        api.put(owner, site + "/access/" + UUID.randomUUID() + "/labor-rate", NONE).andExpect(status().isNotFound());

        api.get(owner, anaRate).andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void removingThePersonKeepsTheHistoryAndReAddingBringsItBack() throws Exception {
        api.put(owner, anaRate, fixed("650.00")).andExpect(status().isOk());
        String accessId = anaRate.substring((site + "/access/").length(), anaRate.length() - "/labor-rate".length());

        api.delete(owner, site + "/access/" + accessId).andExpect(status().isNoContent());
        api.get(owner, anaRate).andExpect(status().isNotFound());

        String readded = JsonPath.read(api.post(owner, site + "/access", "{\"membershipId\":\"" + anaMembership + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.laborRate.kind").value("FIXED"))
                .andReturn().getResponse().getContentAsString(), "$.accessId");
        api.get(owner, site + "/access/" + readded + "/labor-rate").andExpect(status().isOk())
                .andExpect(jsonPath("$.current.dailyAmount").value("650.00"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }
}

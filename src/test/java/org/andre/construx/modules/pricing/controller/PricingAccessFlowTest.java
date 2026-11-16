package org.andre.construx.modules.pricing.controller;

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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seeded role matrix: Owner, Administrator and Finance manage prices, Member only reads the Catalog, Worker sees nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PricingAccessFlowTest {
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
    private UUID orgId;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        members.add(orgId, session.user(), role);
        return session;
    }

    /** One Material with a cost, so every read endpoint has something to show. */
    private String seedResource() {
        CatalogSeed seed = new CatalogSeed(jdbc, orgId);
        seed.catalog();
        UUID unit = seed.unit("SACO", "Saco");
        seed.family("STEEL", "Acero");
        UUID resource = seed.resource("MAT-001", "MATERIAL", "Cemento", unit);
        seed.cost(resource, "10.00", Instant.parse("2026-10-01T16:00:00Z"), "MANUAL", null, null, null, null);
        return resource.toString();
    }

    /** Every read endpoint answers with the given status for the session. */
    private void reads(Session s, String resource, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        for (String path : new String[] {"/catalog", "/resources", "/resources/" + resource,
                "/resources/" + resource + "/costs", "/units", "/families", "/suppliers"}) {
            api.get(s, base + path).andExpect(expected);
        }
    }

    /** Runs the supplier write flow as the given session and expects every step to succeed. */
    private void managesSuppliers(Session s, String tag) throws Exception {
        String supplier = Api.id(api.post(s, base + "/suppliers", "{\"name\":\"S " + tag + "\"}")
                .andExpect(status().isCreated()));
        api.patch(s, base + "/suppliers/" + supplier, "{\"branch\":\"Centro\"}").andExpect(status().isOk());
        api.post(s, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isOk());
    }

    @Test
    void ownerAdministratorAndFinanceReadTheCatalogAndManageSuppliers() throws Exception {
        String resource = seedResource();
        for (Session s : new Session[] {owner, as("ADMINISTRATOR"), as("FINANCE")}) {
            reads(s, resource, status().isOk());
        }
        managesSuppliers(owner, "owner");
        managesSuppliers(as("ADMINISTRATOR"), "admin");
        managesSuppliers(as("FINANCE"), "finance");
    }

    @Test
    void memberReadsButCannotWrite() throws Exception {
        String resource = seedResource();
        String supplier = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Depot\"}"));
        Session member = as("MEMBER");
        reads(member, resource, status().isOk());
        api.get(member, base + "/resources/" + resource).andExpect(jsonPath("$.currentCost").value("10.00"));
        api.post(member, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        api.patch(member, base + "/suppliers/" + supplier, "{\"name\":\"X\"}").andExpect(status().isForbidden());
        api.post(member, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isForbidden());
        assertEquals(1L, jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId));
    }

    @Test
    void workerCannotEvenRead() throws Exception {
        String resource = seedResource();
        Session worker = as("WORKER");
        reads(worker, resource, status().isForbidden());
        api.post(worker, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void nonMembersGetNotFoundEverywhere() throws Exception {
        String resource = seedResource();
        Session stranger = api.signUp();
        reads(stranger, resource, status().isNotFound());
        api.post(stranger, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationsResourceIsNotFound() throws Exception {
        String resource = seedResource();
        Session other = api.signUp();
        String otherBase = "/api/organizations/" + api.createOrganization(other, "Other " + UUID.randomUUID());
        api.get(other, otherBase + "/resources/" + resource).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        api.get(other, otherBase + "/resources/" + resource + "/costs").andExpect(status().isNotFound());
        api.get(other, otherBase + "/resources").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        api.get(other, otherBase + "/units").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.get(other, otherBase + "/families").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theOldMaterialAndPriceObservationRoutesAreGone() throws Exception {
        String resource = seedResource();
        api.get(owner, base + "/materials").andExpect(status().isNotFound());
        api.get(owner, base + "/materials/" + resource).andExpect(status().isNotFound());
        api.post(owner, base + "/materials", "{\"name\":\"X\",\"unit\":\"BAG\",\"category\":\"STEEL\"}")
                .andExpect(status().isNotFound());
        api.post(owner, base + "/price-observations", "{}").andExpect(status().isNotFound());
        api.delete(owner, base + "/price-observations/" + UUID.randomUUID()).andExpect(status().isNotFound());
        api.post(owner, base + "/price-observations/import", null).andExpect(status().isNotFound());
    }

    @Test
    void writesWithoutTheCsrfTokenAreRejected() throws Exception {
        mvc.perform(post(base + "/suppliers").cookie(owner.access())
                        .contentType("application/json").content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0L, jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId));
    }
}

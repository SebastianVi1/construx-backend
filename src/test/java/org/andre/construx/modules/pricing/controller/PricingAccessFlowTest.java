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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seeded role matrix on the catalog reads: Owner, Administrator, Member and Finance read the Catalog, Worker gets
 * 403 and a non-member 404. The former material and price-observation routes are gone.
 */
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
    private List<String> reads;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        CatalogRows rows = new CatalogRows(jdbc, orgId);
        rows.catalog();
        UUID resource = rows.resource("MAT-1", "MATERIAL", "Arena", rows.unit("M3", null)).insert();
        rows.cost(resource, "10", Instant.now(), null, null);
        reads = List.of(base + "/catalog", base + "/units", base + "/families", base + "/suppliers",
                base + "/resources", base + "/resources/" + resource, base + "/resources/" + resource + "/costs");
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        members.add(orgId, session.user(), role);
        return session;
    }

    @Test
    void ownerAdministratorMemberAndFinanceReadTheCatalog() throws Exception {
        for (Session reader : List.of(owner, as("ADMINISTRATOR"), as("MEMBER"), as("FINANCE"))) {
            for (String path : reads) {
                api.get(reader, path).andExpect(status().isOk());
            }
        }
    }

    @Test
    void workerCannotRead() throws Exception {
        Session worker = as("WORKER");
        for (String path : reads) {
            api.get(worker, path).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        api.post(worker, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void nonMembersGetNotFoundEverywhere() throws Exception {
        Session stranger = api.signUp();
        for (String path : reads) {
            api.get(stranger, path).andExpect(status().isNotFound());
        }
        api.post(stranger, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isNotFound());
    }

    @Test
    void suppliersStillManageAsBefore() throws Exception {
        String supplier = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Depot\"}")
                .andExpect(status().isCreated()));
        api.patch(owner, base + "/suppliers/" + supplier, "{\"branch\":\"Centro\"}").andExpect(status().isOk());
        api.post(owner, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isOk());
        Session member = as("MEMBER");
        api.post(member, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void theFormerMaterialAndPriceObservationRoutesAreGone() throws Exception {
        api.get(owner, base + "/materials").andExpect(status().isNotFound());
        api.get(owner, base + "/materials/" + UUID.randomUUID()).andExpect(status().isNotFound());
        api.post(owner, base + "/materials", "{\"name\":\"X\",\"unit\":\"BAG\",\"category\":\"STEEL\"}")
                .andExpect(status().isNotFound());
        api.patch(owner, base + "/materials/" + UUID.randomUUID(), "{\"name\":\"X\"}").andExpect(status().isNotFound());
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

    @Test
    void anOwnerOfAnotherOrganizationGetsNotFoundOnEveryCatalogRead() throws Exception {
        Session otherOwner = api.signUp();
        api.createOrganization(otherOwner, "Other " + UUID.randomUUID());
        for (String path : reads) {
            api.get(otherOwner, path).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void removedRoutesReturnNotFoundForBothGetAndPost() throws Exception {
        for (String path : List.of("/materials", "/materials/" + UUID.randomUUID(),
                "/price-observations", "/price-observations/import")) {
            api.get(owner, base + path).andExpect(status().isNotFound());
            api.post(owner, base + path, "{}").andExpect(status().isNotFound());
        }
    }

}

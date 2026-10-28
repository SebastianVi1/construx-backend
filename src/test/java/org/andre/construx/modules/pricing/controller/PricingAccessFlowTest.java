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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seeded role matrix: Owner, Administrator and Finance manage prices, Member only reads, Worker sees nothing. */
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

    /** Runs the whole write flow as the given session and expects every step to succeed. */
    private void managesPrices(Session s, String tag) throws Exception {
        String material = Api.id(api.post(s, base + "/materials",
                "{\"name\":\"M " + tag + "\",\"unit\":\"BAG\",\"category\":\"STEEL\"}").andExpect(status().isCreated()));
        String supplier = Api.id(api.post(s, base + "/suppliers", "{\"name\":\"S " + tag + "\"}")
                .andExpect(status().isCreated()));
        api.patch(s, base + "/materials/" + material, "{\"brand\":\"B\"}").andExpect(status().isOk());
        api.patch(s, base + "/suppliers/" + supplier, "{\"branch\":\"Centro\"}").andExpect(status().isOk());
        String observation = Api.id(api.post(s, base + "/price-observations", "{\"materialId\":\"" + material
                + "\",\"supplierId\":\"" + supplier + "\",\"price\":10,\"observedOn\":\"2026-10-01\"}")
                .andExpect(status().isCreated()));
        api.get(s, base + "/materials").andExpect(status().isOk());
        api.get(s, base + "/materials/" + material).andExpect(status().isOk());
        api.get(s, base + "/suppliers").andExpect(status().isOk());
        api.delete(s, base + "/price-observations/" + observation).andExpect(status().isNoContent());
        api.post(s, base + "/materials/" + material + "/archive", null).andExpect(status().isOk());
        api.post(s, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isOk());
    }

    @Test
    void ownerAdministratorAndFinanceManagePrices() throws Exception {
        managesPrices(owner, "owner");
        managesPrices(as("ADMINISTRATOR"), "admin");
        managesPrices(as("FINANCE"), "finance");
    }

    @Test
    void memberReadsButCannotWrite() throws Exception {
        String material = Api.id(api.post(owner, base + "/materials",
                "{\"name\":\"Cemento\",\"unit\":\"BAG\",\"category\":\"STEEL\"}"));
        String supplier = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Depot\"}"));
        String observation = Api.id(api.post(owner, base + "/price-observations", "{\"materialId\":\"" + material
                + "\",\"supplierId\":\"" + supplier + "\",\"price\":10,\"observedOn\":\"2026-10-01\"}"));
        Session member = as("MEMBER");
        api.get(member, base + "/materials").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latestPrices[0].price").value("10.00"));
        api.get(member, base + "/materials/" + material).andExpect(status().isOk());
        api.get(member, base + "/suppliers").andExpect(status().isOk());
        api.post(member, base + "/materials", "{\"name\":\"X\",\"unit\":\"BAG\",\"category\":\"STEEL\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        api.patch(member, base + "/materials/" + material, "{\"name\":\"X\"}").andExpect(status().isForbidden());
        api.post(member, base + "/materials/" + material + "/archive", null).andExpect(status().isForbidden());
        api.post(member, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden());
        api.patch(member, base + "/suppliers/" + supplier, "{\"name\":\"X\"}").andExpect(status().isForbidden());
        api.post(member, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isForbidden());
        api.post(member, base + "/price-observations", "{\"materialId\":\"" + material + "\",\"supplierId\":\""
                + supplier + "\",\"price\":10,\"observedOn\":\"2026-10-01\"}").andExpect(status().isForbidden());
        api.delete(member, base + "/price-observations/" + observation).andExpect(status().isForbidden());
        assertEquals(1L, jdbc.queryForObject("select count(*) from material where organization_id = ?", Long.class, orgId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from price_observation where organization_id = ?",
                Long.class, orgId));
        assertEquals("Cemento", jdbc.queryForObject("select name from material where id = ?", String.class,
                UUID.fromString(material)));
    }

    @Test
    void workerCannotEvenRead() throws Exception {
        String material = Api.id(api.post(owner, base + "/materials",
                "{\"name\":\"Cemento\",\"unit\":\"BAG\",\"category\":\"STEEL\"}"));
        Session worker = as("WORKER");
        api.get(worker, base + "/materials").andExpect(status().isForbidden());
        api.get(worker, base + "/materials/" + material).andExpect(status().isForbidden());
        api.get(worker, base + "/suppliers").andExpect(status().isForbidden());
        api.post(worker, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void nonMembersGetNotFoundEverywhere() throws Exception {
        String material = Api.id(api.post(owner, base + "/materials",
                "{\"name\":\"Cemento\",\"unit\":\"BAG\",\"category\":\"STEEL\"}"));
        Session stranger = api.signUp();
        api.get(stranger, base + "/materials").andExpect(status().isNotFound());
        api.get(stranger, base + "/materials/" + material).andExpect(status().isNotFound());
        api.post(stranger, base + "/materials", "{\"name\":\"X\",\"unit\":\"BAG\",\"category\":\"STEEL\"}")
                .andExpect(status().isNotFound());
        api.get(stranger, base + "/suppliers").andExpect(status().isNotFound());
        api.post(stranger, base + "/suppliers", "{\"name\":\"X\"}").andExpect(status().isNotFound());
        api.delete(stranger, base + "/price-observations/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void writesWithoutTheCsrfTokenAreRejected() throws Exception {
        mvc.perform(post(base + "/suppliers").cookie(owner.access())
                        .contentType("application/json").content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0L, jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId));
    }
}

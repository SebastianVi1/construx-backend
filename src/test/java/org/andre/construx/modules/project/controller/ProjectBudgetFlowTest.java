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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Site budget: Owner, Administrator and Finance read and set it; everyone else gets null and 403. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectBudgetFlowTest {
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
    private String base;
    private String siteId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId + "/projects";
        siteId = Api.id(api.post(owner, base, "{\"name\":\"Tower\"}"));
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        UUID membership = members.add(orgId, session.user(), role);
        members.grantSite(UUID.fromString(siteId), membership);
        return session;
    }

    @Test
    void newSiteHasNoBudgetAndOwnerSetsAndClearsIt() throws Exception {
        api.get(owner, base + "/" + siteId).andExpect(jsonPath("$.allocatedBudget").isEmpty());
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":250000.5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(siteId))
                .andExpect(jsonPath("$.allocatedBudget").value(250000.5));
        api.get(owner, base + "/" + siteId).andExpect(jsonPath("$.allocatedBudget").value(250000.5));
        api.get(owner, base).andExpect(jsonPath("$[0].allocatedBudget").value(250000.5));
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").isEmpty());
    }

    @Test
    void administratorAndFinanceManageTheBudget() throws Exception {
        Session admin = as("ADMINISTRATOR");
        Session finance = as("FINANCE");
        api.put(admin, base + "/" + siteId + "/budget", "{\"allocatedBudget\":1000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocatedBudget").value(1000));
        api.put(finance, base + "/" + siteId + "/budget", "{\"allocatedBudget\":2000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.allocatedBudget").value(2000));
        api.get(admin, base + "/" + siteId).andExpect(jsonPath("$.allocatedBudget").value(2000));
        api.get(finance, base).andExpect(jsonPath("$[0].allocatedBudget").value(2000));
    }

    @Test
    void memberAndWorkerNeitherSeeNorChangeTheBudget() throws Exception {
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":5000}").andExpect(status().isOk());
        for (String role : new String[] {"MEMBER", "WORKER"}) {
            Session s = as(role);
            api.get(s, base + "/" + siteId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.allocatedBudget").isEmpty());
            api.get(s, base).andExpect(jsonPath("$[0].allocatedBudget").isEmpty());
            api.put(s, base + "/" + siteId + "/budget", "{\"allocatedBudget\":1}").andExpect(status().isForbidden());
        }
        api.get(owner, base + "/" + siteId).andExpect(jsonPath("$.allocatedBudget").value(5000));
    }

    @Test
    void invalidAmountsAreRejectedAndKeepTheStoredValue() throws Exception {
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":5000}").andExpect(status().isOk());
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":-1}").andExpect(status().isBadRequest());
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":1.234}").andExpect(status().isBadRequest());
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":1000000000000}")
                .andExpect(status().isBadRequest());
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":\"abc\"}")
                .andExpect(status().isBadRequest());
        api.get(owner, base + "/" + siteId).andExpect(jsonPath("$.allocatedBudget").value(5000));
    }

    @Test
    void otherTenantsAndUnknownSitesGetNotFound() throws Exception {
        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.put(stranger, base + "/" + siteId + "/budget", "{\"allocatedBudget\":1}").andExpect(status().isNotFound());
        api.put(stranger, "/api/organizations/" + strangerOrg + "/projects/" + siteId + "/budget",
                "{\"allocatedBudget\":1}").andExpect(status().isNotFound());
        api.put(owner, base + "/" + UUID.randomUUID() + "/budget", "{\"allocatedBudget\":1}")
                .andExpect(status().isNotFound());
    }

    @Test
    void patchingTheSiteLeavesTheBudgetAlone() throws Exception {
        api.put(owner, base + "/" + siteId + "/budget", "{\"allocatedBudget\":5000}").andExpect(status().isOk());
        api.patch(owner, base + "/" + siteId, "{\"name\":\"Renamed\",\"allocatedBudget\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(5000));
    }
}

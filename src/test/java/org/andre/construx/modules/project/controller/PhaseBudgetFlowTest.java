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

/** Phase budgets are carved out of the site budget; only Owner and Finance change them. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseBudgetFlowTest {
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
    private String goals;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        site = "/api/organizations/" + orgId + "/projects/" + Api.id(api.post(owner,
                "/api/organizations/" + orgId + "/projects", "{\"name\":\"Tower\"}"));
        goals = site + "/goals";
    }

    private Session as(String role) throws Exception {
        Session session = api.signUp();
        UUID membership = members.add(orgId, session.user(), role);
        members.grantSite(UUID.fromString(site.substring(site.lastIndexOf('/') + 1)), membership);
        return session;
    }

    private String phase(String name) throws Exception {
        return goals + "/" + Api.id(api.post(owner, goals, "{\"name\":\"" + name
                + "\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
    }

    private static String budget(String amount) {
        return "{\"allocatedBudget\":" + amount + "}";
    }

    @Test
    void newPhaseHasNoBudgetAndFinanceAssignsChangesAndClearsIt() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        String phase = phase("Foundations");
        api.get(owner, phase).andExpect(jsonPath("$.allocatedBudget").isEmpty())
                .andExpect(jsonPath("$.allocatedToActivities").value(0))
                .andExpect(jsonPath("$.unallocated").isEmpty());

        Session finance = as("FINANCE");
        api.get(finance, goals).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.put(finance, phase + "/budget", budget("400.5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(400.5))
                .andExpect(jsonPath("$.unallocated").value(400.5));
        api.put(owner, phase + "/budget", budget("600")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(600));
        api.get(owner, site + "/budget").andExpect(jsonPath("$.allocatedToPhases").value(600))
                .andExpect(jsonPath("$.unallocated").value(400));
        api.put(finance, phase + "/budget", budget("null")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").isEmpty());
        api.get(owner, site + "/budget").andExpect(jsonPath("$.allocatedToPhases").value(0))
                .andExpect(jsonPath("$.unallocated").value(1000));
    }

    @Test
    void phasesCannotTakeMoreThanTheSiteBudget() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        String a = phase("Foundations");
        String b = phase("Structure");
        api.put(owner, a + "/budget", budget("700")).andExpect(status().isOk());
        api.put(owner, b + "/budget", budget("300.01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
        api.put(owner, b + "/budget", budget("300")).andExpect(status().isOk());
        api.get(owner, b).andExpect(jsonPath("$.allocatedBudget").value(300));
        api.put(owner, a + "/budget", budget("700")).andExpect(status().isOk());
    }

    @Test
    void aPhaseBudgetNeedsASiteBudget() throws Exception {
        String phase = phase("Foundations");
        api.put(owner, phase + "/budget", budget("1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_PARENT_MISSING"));
        api.put(owner, phase + "/budget", budget("null")).andExpect(status().isOk());
    }

    @Test
    void theSiteBudgetCannotDropBelowWhatItsPhasesHold() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        api.put(owner, phase("Foundations") + "/budget", budget("800")).andExpect(status().isOk());
        api.put(owner, site + "/budget", budget("799.99")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
        api.put(owner, site + "/budget", budget("null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"));
        api.put(owner, site + "/budget", budget("800")).andExpect(status().isOk());
        api.get(owner, site).andExpect(jsonPath("$.allocatedBudget").value(800));
    }

    @Test
    void deletingAPhaseReleasesItsBudget() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        String phase = phase("Foundations");
        api.put(owner, phase + "/budget", budget("1000")).andExpect(status().isOk());
        api.delete(owner, phase).andExpect(status().isNoContent());
        api.get(owner, site + "/budget").andExpect(jsonPath("$.unallocated").value(1000));
    }

    @Test
    void administratorMemberAndWorkerCannotChangeAPhaseBudget() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        String phase = phase("Foundations");
        api.put(owner, phase + "/budget", budget("500")).andExpect(status().isOk());
        for (String role : new String[] {"ADMINISTRATOR", "MEMBER", "WORKER"}) {
            api.put(as(role), phase + "/budget", budget("1")).andExpect(status().isForbidden());
        }
        api.get(as("ADMINISTRATOR"), phase).andExpect(jsonPath("$.allocatedBudget").value(500));
        for (String role : new String[] {"MEMBER", "WORKER"}) {
            api.get(as(role), phase).andExpect(status().isOk())
                    .andExpect(jsonPath("$.allocatedBudget").isEmpty())
                    .andExpect(jsonPath("$.allocatedToActivities").isEmpty())
                    .andExpect(jsonPath("$.unallocated").isEmpty());
        }
    }

    @Test
    void invalidBodiesAreRejectedAndKeepTheStoredValue() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        String phase = phase("Foundations");
        api.put(owner, phase + "/budget", budget("100")).andExpect(status().isOk());
        api.put(owner, phase + "/budget", budget("-1")).andExpect(status().isBadRequest());
        api.put(owner, phase + "/budget", budget("1.234")).andExpect(status().isBadRequest());
        api.put(owner, phase + "/budget", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, phase).andExpect(jsonPath("$.allocatedBudget").value(100));
    }

    @Test
    void creatingOrPatchingAPhaseNeverTouchesItsBudget() throws Exception {
        api.put(owner, site + "/budget", budget("1000")).andExpect(status().isOk());
        api.post(owner, goals, "{\"name\":\"X\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\","
                + "\"allocatedBudget\":5}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocatedBudget").isEmpty());
        String phase = phase("Foundations");
        api.put(owner, phase + "/budget", budget("100")).andExpect(status().isOk());
        api.patch(owner, phase, "{\"name\":\"Renamed\",\"allocatedBudget\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.allocatedBudget").value(100));
    }

    @Test
    void otherTenantsAndUnknownPhasesGetNotFound() throws Exception {
        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String phase = phase("Foundations");
        api.put(stranger, phase + "/budget", budget("1")).andExpect(status().isNotFound());
        api.put(owner, goals + "/" + UUID.randomUUID() + "/budget", budget("1")).andExpect(status().isNotFound());
    }
}

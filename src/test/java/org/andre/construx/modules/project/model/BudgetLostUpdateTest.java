package org.andre.construx.modules.project.model;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A budget change writes only its own column, so an edit committed by someone else between loading the row
 * and saving the budget is not overwritten with stale values (and the other way round).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetLostUpdateTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired GoalRepository goals;
    @Autowired ActivityRepository activities;
    @Autowired PlatformTransactionManager txm;

    private UUID goalId;
    private UUID activityId;

    @BeforeEach
    void setUp() throws Exception {
        Api api = new Api(mvc, users, tokens);
        Members members = new Members(txm, organizations, memberships, projects, accesses);
        Session owner = api.signUp();
        UUID orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        UUID projectId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        String site = "/api/organizations/" + orgId + "/projects/" + projectId;
        UUID assignee = members.grantSite(projectId, members.ownerMembership(orgId, owner.user()));
        api.put(owner, site + "/budget", "{\"allocatedBudget\":1000}");
        goalId = UUID.fromString(Api.id(api.post(owner, site + "/goals",
                "{\"name\":\"Foundations\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")));
        api.put(owner, site + "/goals/" + goalId + "/budget", "{\"allocatedBudget\":500}");
        activityId = UUID.fromString(Api.id(api.post(owner, site + "/goals/" + goalId + "/activities",
                "{\"name\":\"Dig\",\"startAt\":\"2027-01-02T00:00:00Z\",\"dueAt\":\"2027-01-10T00:00:00Z\","
                        + "\"assigneeAccessId\":\"" + assignee + "\"}")));
    }

    /** Runs {@code other} in its own committed transaction while {@code first} still holds a stale copy. */
    private void interleave(Runnable first, Runnable other, Runnable firstFinishes) {
        TransactionTemplate outer = new TransactionTemplate(txm);
        TransactionTemplate inner = new TransactionTemplate(txm);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.executeWithoutResult(status -> {
            first.run();
            inner.executeWithoutResult(s -> other.run());
            firstFinishes.run();
        });
    }

    @Test
    void aTaskBudgetDoesNotUndoAConcurrentRename() {
        Activity[] stale = new Activity[1];
        interleave(
                () -> stale[0] = activities.findById(activityId).orElseThrow(),
                () -> activities.findById(activityId).orElseThrow().rename("Dig deeper"),
                () -> stale[0].assignBudget(new BigDecimal("100"), BigDecimal.ZERO));
        Activity saved = new TransactionTemplate(txm).execute(s -> activities.findById(activityId).orElseThrow());
        assertEquals("Dig deeper", saved.getName());
        assertEquals(new BigDecimal("100.00"), saved.getAllocatedBudget());
    }

    @Test
    void aPhaseRenameDoesNotUndoAConcurrentBudget() {
        Goal[] stale = new Goal[1];
        interleave(
                () -> stale[0] = goals.findById(goalId).orElseThrow(),
                () -> {
                    Goal goal = goals.findById(goalId).orElseThrow();
                    goal.assignBudget(new BigDecimal("700"), BigDecimal.ZERO, BigDecimal.ZERO);
                },
                () -> stale[0].rename("Structure"));
        Goal saved = new TransactionTemplate(txm).execute(s -> goals.findById(goalId).orElseThrow());
        assertEquals("Structure", saved.getName());
        assertEquals(new BigDecimal("700.00"), saved.getAllocatedBudget());
    }
}

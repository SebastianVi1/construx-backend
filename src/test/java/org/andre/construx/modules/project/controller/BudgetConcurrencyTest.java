package org.andre.construx.modules.project.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.GoalService;
import org.andre.construx.modules.project.service.ProjectService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Concurrent budget changes on one site are serialized by row locks, so the allocation cap always holds. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetConcurrencyTest {
    private static final String PHASE_DATES = "\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}";

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired ProjectService projectService;
    @Autowired GoalService goalService;
    @Autowired ActivityService activityService;
    @Autowired GoalRepository goals;
    @Autowired ActivityRepository activities;

    private Api api;
    private Session owner;
    private UUID user;
    private UUID orgId;
    private UUID projectId;
    private UUID assignee;
    private String site;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        Members members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        user = owner.user().getId();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        projectId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"T\"}")));
        site = "/api/organizations/" + orgId + "/projects/" + projectId;
        assignee = members.grantSite(projectId, members.ownerMembership(orgId, owner.user()));
        api.put(owner, site + "/budget", "{\"allocatedBudget\":100}").andExpect(status().isOk());
    }

    private UUID phase(String name) throws Exception {
        return UUID.fromString(Api.id(api.post(owner, site + "/goals", "{\"name\":\"" + name + PHASE_DATES)));
    }

    /** Starts every call at once; rejected calls are expected, only the final state is checked. */
    private static void together(int threads, IntFunction<Callable<Object>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Object> call = work.apply(i);
            calls.add(pool.submit(() -> {
                barrier.await();
                try {
                    call.call();
                } catch (IllegalArgumentException rejected) {
                    // BUDGET_EXCEEDED for the losers
                }
                return null;
            }));
        }
        for (Future<?> call : calls) call.get(60, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    void concurrentPhaseBudgetsNeverExceedTheSiteBudget() throws Exception {
        for (int round = 0; round < 3; round++) {
            List<UUID> phases = new ArrayList<>();
            for (int i = 0; i < 6; i++) phases.add(phase("P" + round + i));
            together(6, i -> () -> goalService.assignBudget(user, orgId, projectId, phases.get(i), new BigDecimal("30")));
            BigDecimal sum = goals.sumBudgetByProject(projectId);
            assertTrue(sum.compareTo(new BigDecimal("100")) <= 0, "phases hold " + sum);
            for (UUID g : phases) goalService.assignBudget(user, orgId, projectId, g, null);
        }
    }

    @Test
    void loweringTheSiteWhileRaisingAPhaseKeepsTheCap() throws Exception {
        for (int round = 0; round < 3; round++) {
            projectService.assignBudget(user, orgId, projectId, new BigDecimal("100"));
            UUID g = phase("Q" + round);
            together(2, i -> i == 0
                    ? () -> goalService.assignBudget(user, orgId, projectId, g, new BigDecimal("80"))
                    : () -> projectService.assignBudget(user, orgId, projectId, new BigDecimal("50")));
            BigDecimal sum = goals.sumBudgetByProject(projectId);
            BigDecimal siteBudget = projects.findById(projectId).orElseThrow().getAllocatedBudget();
            assertTrue(sum.compareTo(siteBudget) <= 0, "phases " + sum + " > site " + siteBudget);
            goalService.assignBudget(user, orgId, projectId, g, null);
        }
    }

    @Test
    void concurrentTaskBudgetsNeverExceedThePhaseBudget() throws Exception {
        UUID p = phase("Tasks");
        goalService.assignBudget(user, orgId, projectId, p, new BigDecimal("100"));
        for (int round = 0; round < 3; round++) {
            List<UUID> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(UUID.fromString(Api.id(api.post(owner, site + "/goals/" + p + "/activities", "{\"name\":\"t" + round + i
                        + "\",\"startAt\":\"2027-01-02T00:00:00Z\",\"dueAt\":\"2027-01-10T00:00:00Z\",\"assignees\":[{\"accessId\":\"" + assignee + "\",\"taskRole\":\"LEAD\"}]}"))));
            }
            together(6, i -> i == 5
                    ? () -> goalService.assignBudget(user, orgId, projectId, p, new BigDecimal("40"))
                    : () -> activityService.assignBudget(user, orgId, projectId, p, tasks.get(i), new BigDecimal("30")));
            BigDecimal sum = activities.sumBudgetOfGoal(p);
            BigDecimal phaseBudget = goals.findById(p).orElseThrow().getAllocatedBudget();
            assertTrue(sum.compareTo(phaseBudget) <= 0, "tasks " + sum + " > phase " + phaseBudget);
            for (UUID t : tasks) activityService.assignBudget(user, orgId, projectId, p, t, null);
            goalService.assignBudget(user, orgId, projectId, p, new BigDecimal("100"));
        }
    }
}

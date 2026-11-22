package org.andre.construx.modules.organization.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.andre.construx.modules.organization.model.GuideProgress;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.repository.GuideProgressRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The getting-started guide progress endpoints of docs/guide.md, through real cookies and CSRF. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuideFlowTest {
    private static final String COMPLETED = "{\"status\":\"COMPLETED\"}";

    @TestConfiguration
    static class Storage {
        @Bean @Primary ObjectStorage inMemoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @MockitoSpyBean GuideProgressRepository progressRows;
    @PersistenceContext EntityManager entityManager;

    private Api api;
    private Session owner, worker;
    private String guide;
    private UUID orgId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        Members members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        guide = "/api/organizations/" + orgId + "/guide";
        worker = api.signUp();
        members.add(orgId, worker.user(), "WORKER");
    }

    @Test
    void newMemberHasNotStarted() throws Exception {
        api.get(owner, guide).andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.steps").value(anEmptyMap()))
                .andExpect(jsonPath("$.dismissedAt").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").value(nullValue()));
    }

    @Test
    void markingCreatesProgress() throws Exception {
        api.put(owner, guide + "/steps/create-site", COMPLETED).andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.steps.create-site").value("COMPLETED"))
                .andExpect(jsonPath("$.dismissedAt").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").isString());

        api.get(owner, guide).andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps.create-site").value("COMPLETED"));
    }

    @Test
    void markingTwiceIsHarmless() throws Exception {
        api.put(owner, guide + "/steps/create-site", COMPLETED).andExpect(status().isOk());
        api.put(owner, guide + "/steps/create-site", COMPLETED).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps.create-site").value("COMPLETED"));
    }

    @Test
    void concurrentFirstInsertRetriesAgainstExistingProgress() throws Exception {
        api.put(owner, guide + "/steps/create-site", COMPLETED).andExpect(status().isOk());
        UUID membershipId = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();
        var existing = progressRows.findById(membershipId).orElseThrow();
        doReturn(Optional.empty()).doReturn(Optional.of(existing)).when(progressRows).findById(eq(membershipId));

        api.put(owner, guide + "/steps/invite-member", COMPLETED).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.create-site").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.invite-member").value("COMPLETED"));
        api.get(owner, guide).andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    void aSkippedStepCanBeCompletedLater() throws Exception {
        api.put(owner, guide + "/steps/invite-member", "{\"status\":\"SKIPPED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.invite-member").value("SKIPPED"));
        api.put(owner, guide + "/steps/invite-member", COMPLETED).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.invite-member").value("COMPLETED"));
    }

    @Test
    void invalidStepIdIs400() throws Exception {
        api.put(owner, guide + "/steps/Bad_Id", COMPLETED).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUIDE_STEP_INVALID"));
        api.put(owner, guide + "/steps/" + "a".repeat(65), COMPLETED).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUIDE_STEP_INVALID"));
        api.get(owner, guide).andExpect(jsonPath("$.started").value(false));
    }

    @Test
    void theFiftyFirstStepIs400() throws Exception {
        for (int i = 0; i < 50; i++) {
            api.put(owner, guide + "/steps/step-" + i, COMPLETED).andExpect(status().isOk());
        }

        api.put(owner, guide + "/steps/step-50", COMPLETED).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUIDE_TOO_MANY_STEPS"));
        api.put(owner, guide + "/steps/step-3", "{\"status\":\"SKIPPED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(50));
    }

    @Test
    void concurrentAdditionsCannotExceedFiftySteps() throws Exception {
        for (int i = 0; i < 49; i++) {
            api.put(owner, guide + "/steps/step-" + i, COMPLETED).andExpect(status().isOk());
        }
        UUID membershipId = memberships.findByOrganizationIdAndUserAccountId(orgId, owner.user().getId())
                .orElseThrow().getId();
        CountDownLatch bothRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            var progress = Optional.ofNullable(entityManager.find(GuideProgress.class, membershipId));
            bothRead.countDown();
            bothRead.await(500, TimeUnit.MILLISECONDS);
            return progress;
        }).when(progressRows).findById(eq(membershipId));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> api.put(owner, guide + "/steps/race-a", COMPLETED)
                    .andReturn().getResponse());
            var second = pool.submit(() -> api.put(owner, guide + "/steps/race-b", COMPLETED)
                    .andReturn().getResponse());
            var firstResponse = first.get(15, TimeUnit.SECONDS);
            var secondResponse = second.get(15, TimeUnit.SECONDS);
            assertEquals(Set.of(200, 400), Set.copyOf(List.of(firstResponse.getStatus(), secondResponse.getStatus())));
            var rejected = firstResponse.getStatus() == 400 ? firstResponse : secondResponse;
            assertEquals("GUIDE_TOO_MANY_STEPS", JsonPath.read(rejected.getContentAsString(), "$.code"));
            api.get(owner, guide).andExpect(jsonPath("$.steps.length()").value(50));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aStepNeedsAKnownStatus() throws Exception {
        api.put(owner, guide + "/steps/create-site", "{}").andExpect(status().isBadRequest());
        api.put(owner, guide + "/steps/create-site", "{\"status\":\"DONE\"}").andExpect(status().isBadRequest());
        api.get(owner, guide).andExpect(jsonPath("$.started").value(false));
    }

    @Test
    void dismissRoundTrip() throws Exception {
        String first = JsonPath.read(api.put(owner, guide, "{\"dismissed\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.steps").value(anEmptyMap()))
                .andReturn().getResponse().getContentAsString(), "$.dismissedAt");
        api.put(owner, guide, "{\"dismissed\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissedAt").value(first));

        api.put(owner, guide, "{\"dismissed\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.dismissedAt").value(nullValue()));
        api.get(owner, guide).andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.dismissedAt").value(nullValue()));
    }

    @Test
    void showingTheGuideStartsIt() throws Exception {
        api.put(owner, guide, "{\"dismissed\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.dismissedAt").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").isString());
        api.put(owner, guide, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void progressIsPerMember() throws Exception {
        api.put(worker, guide + "/steps/open-site", COMPLETED).andExpect(status().isOk());

        api.get(owner, guide).andExpect(jsonPath("$.started").value(false));
        api.get(worker, guide).andExpect(jsonPath("$.steps.open-site").value("COMPLETED"));
    }

    @Test
    void progressIsPerCompany() throws Exception {
        String other = "/api/organizations/" + api.createOrganization(owner, "Other " + UUID.randomUUID()) + "/guide";

        api.put(owner, guide + "/steps/create-site", COMPLETED).andExpect(status().isOk());

        api.get(owner, other).andExpect(jsonPath("$.started").value(false));
        api.put(owner, other, "{\"dismissed\":true}").andExpect(status().isOk());
        api.get(owner, guide).andExpect(jsonPath("$.dismissedAt").value(nullValue()));
    }

    @Test
    void nonMemberGets404() throws Exception {
        Session stranger = api.signUp();

        api.get(stranger, guide).andExpect(status().isNotFound());
        api.put(stranger, guide + "/steps/create-site", COMPLETED).andExpect(status().isNotFound());
        api.put(stranger, guide, "{\"dismissed\":true}").andExpect(status().isNotFound());
        api.get(stranger, "/api/organizations/" + UUID.randomUUID() + "/guide").andExpect(status().isNotFound());
        api.get(owner, guide).andExpect(jsonPath("$.started").value(false));
    }

    @Test
    void csrfIsRequired() throws Exception {
        mvc.perform(put(guide + "/steps/create-site").cookie(owner.access())
                        .contentType("application/json").content(COMPLETED))
                .andExpect(status().isForbidden());
        mvc.perform(put(guide).cookie(owner.access())
                        .contentType("application/json").content("{\"dismissed\":true}"))
                .andExpect(status().isForbidden());

        api.get(owner, guide).andExpect(jsonPath("$.started").value(false));
    }
}

package org.andre.construx.modules.plan.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage.PresignedUpload;
import org.andre.construx.shared.storage.ObjectStorage.StoredObject;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectPlanFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private UUID projectId;
    private String plans;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        projectId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        plans = "/api/organizations/" + orgId + "/projects/" + projectId + "/plans";
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any(Duration.class)))
                .thenAnswer(call -> new PresignedUpload(URI.create("https://bucket.test/" + call.getArgument(0)),
                        Map.of("Content-Type", call.getArgument(1)), Instant.parse("2030-01-01T00:00:00Z")));
        when(storage.presignDownload(anyString(), any(Duration.class)))
                .thenAnswer(call -> URI.create("https://bucket.test/view/" + call.getArgument(0)));
    }

    private String upload(String fileName, String contentType, long size) {
        return "{\"fileName\":\"%s\",\"contentType\":\"%s\",\"sizeBytes\":%d}".formatted(fileName, contentType, size);
    }

    private String requestUpload(String fileName, String contentType, long size) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(api.post(owner, plans + "/uploads", upload(fileName, contentType, size))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.planId");
    }

    private String storageKey(String planId) {
        return jdbc.queryForObject("select storage_key from project_plan where id = ?", String.class, UUID.fromString(planId));
    }

    @Test
    void ownerUploadsAPlanThroughStorageAndItIsListedOnceComplete() throws Exception {
        String body = api.post(owner, plans + "/uploads",
                        "{\"fileName\":\"Ground floor.png\",\"contentType\":\"image/png\",\"sizeBytes\":2048,\"description\":\" Level 0 \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planId").isNotEmpty())
                .andExpect(jsonPath("$.headers['Content-Type']").value("image/png"))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        String planId = com.jayway.jsonpath.JsonPath.read(body, "$.planId");
        String key = storageKey(planId);
        assertTrue(key.startsWith("orgs/" + orgId + "/projects/" + projectId + "/plans/" + planId + "/"), key);
        assertTrue(key.endsWith(".png"), key);
        assertEquals("https://bucket.test/" + key, com.jayway.jsonpath.JsonPath.read(body, "$.uploadUrl"));
        verify(storage).presignUpload(eq(key), eq("image/png"), eq(2048L), eq(Duration.ofMinutes(10)));

        api.get(owner, plans).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        api.get(owner, plans + "/" + planId).andExpect(status().isNotFound());

        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(2048, "image/png")));
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId))
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Ground floor"))
                .andExpect(jsonPath("$.description").value("Level 0"))
                .andExpect(jsonPath("$.fileName").value("Ground floor.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(2048))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.viewUrl").value("https://bucket.test/view/" + key))
                .andExpect(jsonPath("$.viewUrlExpiresAt").isNotEmpty());
        assertEquals("READY", jdbc.queryForObject("select status from project_plan where id = ?", String.class,
                UUID.fromString(planId)));

        api.get(owner, plans).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(planId))
                .andExpect(jsonPath("$[0].viewUrl").value("https://bucket.test/view/" + key));
        api.get(owner, plans + "/" + planId).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ground floor"));
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        api.delete(owner, plans + "/" + planId).andExpect(status().isNoContent());
        verify(storage).delete(key);
        api.get(owner, plans).andExpect(jsonPath("$.length()").value(0));
        assertEquals(0, jdbc.queryForObject("select count(*) from project_plan where id = ?", Integer.class,
                UUID.fromString(planId)));
    }

    @Test
    void anExplicitNameWinsOverTheFileName() throws Exception {
        api.post(owner, plans + "/uploads",
                        "{\"fileName\":\"scan.jpg\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10,\"name\":\" Structure \"}")
                .andExpect(status().isCreated());
        assertEquals("Structure", jdbc.queryForObject("select name from project_plan where project_id = ?",
                String.class, projectId));
    }

    @Test
    void wrongTypesSizesAndShapesAreRejectedWithoutTouchingStorage() throws Exception {
        api.post(owner, plans + "/uploads", upload("plan.pdf", "application/pdf", 10))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN_FILE_TYPE"));
        api.post(owner, plans + "/uploads", upload("plan.png", "image/png", 20L * 1024 * 1024 + 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN_FILE_TOO_LARGE"));
        api.post(owner, plans + "/uploads", upload("plan.png", "image/png", 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, plans + "/uploads", "{\"contentType\":\"image/png\",\"sizeBytes\":10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, plans + "/uploads",
                        "{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"sizeBytes\":10,\"name\":\"" + "x".repeat(121) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(0, jdbc.queryForObject("select count(*) from project_plan where project_id = ?", Integer.class,
                projectId));
        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong(), any(Duration.class));
    }

    @Test
    void completingWithoutTheFileOrWithADifferentFileKeepsThePlanPending() throws Exception {
        String planId = requestUpload("plan.webp", "image/webp", 500);
        String key = storageKey(planId);
        when(storage.head(key)).thenReturn(Optional.empty());
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_UPLOAD_MISSING"));
        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(501, "image/webp")));
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_UPLOAD_MISMATCH"));
        assertEquals("PENDING", jdbc.queryForObject("select status from project_plan where id = ?", String.class,
                UUID.fromString(planId)));
    }

    @Test
    void aFailingStorageDeleteKeepsThePlan() throws Exception {
        String planId = requestUpload("plan.png", "image/png", 10);
        doThrow(new RuntimeException("storage down")).when(storage).delete(storageKey(planId));
        api.delete(owner, plans + "/" + planId).andExpect(status().isInternalServerError());
        assertEquals(1, jdbc.queryForObject("select count(*) from project_plan where id = ?", Integer.class,
                UUID.fromString(planId)));
    }

    @Test
    void membersManagePlansOnTheirSitesWorkersOnlyViewThemFinanceSeesNothing() throws Exception {
        Session member = api.signUp();
        UUID memberMembership = members.add(orgId, member.user(), "MEMBER");
        Session worker = api.signUp();
        UUID workerMembership = members.add(orgId, worker.user(), "WORKER");
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        String planId = requestUpload("plan.png", "image/png", 10);
        when(storage.head(storageKey(planId))).thenReturn(Optional.of(new StoredObject(10, "image/png")));
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isOk());

        api.get(member, plans).andExpect(status().isForbidden());
        api.post(member, plans + "/uploads", upload("m.png", "image/png", 10)).andExpect(status().isForbidden());
        members.grantSite(projectId, memberMembership);
        api.get(member, plans).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.post(member, plans + "/uploads", upload("m.png", "image/png", 10)).andExpect(status().isCreated());

        api.get(worker, plans).andExpect(status().isForbidden());
        members.grantSite(projectId, workerMembership);
        api.get(worker, plans).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.get(worker, plans + "/" + planId).andExpect(status().isOk());
        api.post(worker, plans + "/uploads", upload("w.png", "image/png", 10)).andExpect(status().isForbidden());
        api.post(worker, plans + "/" + planId + "/complete", "").andExpect(status().isForbidden());
        api.delete(worker, plans + "/" + planId).andExpect(status().isForbidden());

        api.get(finance, plans).andExpect(status().isForbidden());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void otherTenantsAndOtherSitesGetNotFound() throws Exception {
        String planId = requestUpload("plan.png", "image/png", 10);
        when(storage.head(storageKey(planId))).thenReturn(Optional.of(new StoredObject(10, "image/png")));
        api.post(owner, plans + "/" + planId + "/complete", "").andExpect(status().isOk());
        String otherSite = Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"Other\"}"));
        String otherPlans = "/api/organizations/" + orgId + "/projects/" + otherSite + "/plans/" + planId;
        api.get(owner, otherPlans).andExpect(status().isNotFound());
        api.delete(owner, otherPlans).andExpect(status().isNotFound());
        api.post(owner, otherPlans + "/complete", "").andExpect(status().isNotFound());

        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, plans).andExpect(status().isNotFound());
        api.get(stranger, plans + "/" + planId).andExpect(status().isNotFound());
        api.post(stranger, plans + "/uploads", upload("s.png", "image/png", 10)).andExpect(status().isNotFound());
        api.delete(stranger, plans + "/" + planId).andExpect(status().isNotFound());
        api.get(owner, plans + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        verify(storage, never()).delete(anyString());
    }
}

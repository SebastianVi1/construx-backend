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
class PlanLibraryFlowTest {
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

    @Test
    void foldersPreservePlansAndSiteOnDeletion() throws Exception {
        String folders = "/api/organizations/" + orgId + "/plan-folders";
        String parent = Api.id(api.post(owner, folders, body("Structure", null)).andExpect(status().isCreated()));
        String child = Api.id(api.post(owner, folders, body("Level 1", parent)).andExpect(status().isCreated()));
        api.patch(owner, folders + "/" + child, body("Renamed", null)).andExpect(status().isOk());
        api.patch(owner, folders + "/" + child, body("Renamed", parent)).andExpect(status().isOk());
        String plan = readyPlan(plans);
        api.put(owner, plans + "/" + plan + "/folder", "{\"folderId\":\"" + parent + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectId").value(projectId.toString()));
        api.get(owner, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.folders.length()").value(2))
                .andExpect(jsonPath("$.plans[0].folderId").value(parent));
        api.delete(owner, folders + "/" + parent).andExpect(status().isNoContent());
        api.get(owner, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0].id").value(child))
                .andExpect(jsonPath("$.folders[0].parentId").isEmpty())
                .andExpect(jsonPath("$.plans[0].id").value(plan))
                .andExpect(jsonPath("$.plans[0].folderId").isEmpty())
                .andExpect(jsonPath("$.plans[0].projectId").value(projectId.toString()));
        api.get(owner, plans + "/" + plan).andExpect(status().isOk());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void libraryFiltersByPlanPermissionAndMembersCannotEditSharedFolders() throws Exception {
        String folderPath = "/api/organizations/" + orgId + "/plan-folders";
        String folder = Api.id(api.post(owner, folderPath, body("Shared", null)).andExpect(status().isCreated()));
        String plan = readyPlan(plans);
        String otherSite = Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"Hidden site\"}"));
        readyPlan("/api/organizations/" + orgId + "/projects/" + otherSite + "/plans");
        Session member = api.signUp();
        UUID membership = members.add(orgId, member.user(), "MEMBER");
        api.get(member, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(0)).andExpect(jsonPath("$.sites.length()").value(0));
        members.grantSite(projectId, membership);
        api.get(member, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(1)).andExpect(jsonPath("$.plans[0].id").value(plan))
                .andExpect(jsonPath("$.sites.length()").value(1))
                .andExpect(jsonPath("$.sites[0].canManage").value(true))
                .andExpect(jsonPath("$.canManageFolders").value(false));
        api.post(member, folderPath, body("Denied", null)).andExpect(status().isForbidden());
        api.patch(member, folderPath + "/" + folder, body("Denied", null)).andExpect(status().isForbidden());
        api.delete(member, folderPath + "/" + folder).andExpect(status().isForbidden());
        api.put(member, plans + "/" + plan + "/folder", "{\"folderId\":\"" + folder + "\"}")
                .andExpect(status().isOk());
        Session worker = api.signUp();
        members.grantSite(projectId, members.add(orgId, worker.user(), "WORKER"));
        api.get(worker, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].folderId").value(folder))
                .andExpect(jsonPath("$.sites[0].canManage").value(false));
        api.put(worker, plans + "/" + plan + "/folder", "{\"folderId\":null}").andExpect(status().isForbidden());
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        api.get(finance, library()).andExpect(status().isForbidden());
        Session admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");
        api.patch(admin, folderPath + "/" + folder, body("Admin renamed", null)).andExpect(status().isOk());
        api.get(worker, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0].name").value("Admin renamed"));
    }

    @Test
    void cyclesInvalidNamesAndForeignFoldersAreRejectedWithoutChanges() throws Exception {
        String path = "/api/organizations/" + orgId + "/plan-folders";
        String parent = Api.id(api.post(owner, path, body("Root", null)).andExpect(status().isCreated()));
        String child = Api.id(api.post(owner, path, body("Child", parent)).andExpect(status().isCreated()));
        api.patch(owner, path + "/" + parent, body("Root", child)).andExpect(status().isBadRequest());
        api.patch(owner, path + "/" + parent, body("Root", parent)).andExpect(status().isBadRequest());
        api.post(owner, path, body("  ", null)).andExpect(status().isBadRequest());
        api.post(owner, path, body("x".repeat(121), null)).andExpect(status().isBadRequest());
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String foreignPath = "/api/organizations/" + otherOrg + "/plan-folders";
        String foreign = Api.id(api.post(stranger, foreignPath, body("Foreign", null)).andExpect(status().isCreated()));
        api.get(stranger, library()).andExpect(status().isNotFound());
        api.post(owner, path, body("Forbidden parent", foreign)).andExpect(status().isNotFound());
        api.patch(owner, path + "/" + parent, body("Root", foreign)).andExpect(status().isNotFound());
        api.delete(owner, path + "/" + foreign).andExpect(status().isNotFound());
        String plan = readyPlan(plans);
        api.put(owner, plans + "/" + plan + "/folder", "{\"folderId\":\"" + foreign + "\"}")
                .andExpect(status().isNotFound());
        api.get(owner, library()).andExpect(status().isOk())
                .andExpect(jsonPath("$.folders.length()").value(2))
                .andExpect(jsonPath("$.folders[?(@.id == '" + parent + "')].parentId").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.plans[0].folderId").isEmpty());
        verify(storage, never()).delete(anyString());
    }

    private String library() { return "/api/organizations/" + orgId + "/plan-library"; }
    private String body(String name, String parent) {
        return "{\"name\":\"" + name + "\",\"parentId\":" + (parent == null ? "null" : "\"" + parent + "\"") + "}";
    }
    private String readyPlan(String path) throws Exception {
        String json = api.post(owner, path + "/uploads", "{\"fileName\":\"plan.png\",\"contentType\":\"image/png\",\"sizeBytes\":10}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(json, "$.planId");
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(10, "image/png")));
        api.post(owner, path + "/" + id + "/complete", "").andExpect(status().isOk());
        return id;
    }
}

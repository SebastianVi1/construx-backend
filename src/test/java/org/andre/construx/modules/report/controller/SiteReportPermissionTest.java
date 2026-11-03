package org.andre.construx.modules.report.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.support.Api;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;



import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the site-report endpoints enforce membership and MANAGE_PROJECT:
 *   - non-member → 404 (MembershipGuard)
 *   - member without MANAGE_PROJECT → 403 (generate only)
 *   - member without MANAGE_PROJECT → can list and get download URL
 *   - member with MANAGE_PROJECT → can generate (201)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SiteReportPermissionTest {

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
    private Api.Session owner;
    private Api.Session viewer;    // org member, site member, role without MANAGE_PROJECT
    private Api.Session outsider;  // not a member of the org at all
    private UUID orgId;
    private UUID siteId;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        api     = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);

        owner    = api.signUp();
        viewer   = api.signUp();
        outsider = api.signUp();

        orgId  = api.createOrganization(owner, "Constructora " + UUID.randomUUID());
        siteId = UUID.fromString(Api.id(api.post(owner,
                "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Obra Test " + UUID.randomUUID() + "\"}")));
        base   = "/api/organizations/" + orgId + "/projects/" + siteId + "/reports";

        // viewer: org member (WORKER role = no MANAGE_PROJECT) + site access
        UUID viewerMembership = members.add(orgId, viewer.user(), "WORKER");
        members.grantSite(siteId, viewerMembership);

        // ObjectStorage mock: put is a no-op; presign returns a dummy URL
        doNothing().when(storage).put(anyString(), any(byte[].class), anyString());
        when(storage.presignDownload(anyString(), any(Duration.class)))
                .thenReturn(URI.create("https://s3.example.com/report.pdf"));
    }

    // ── generate (POST) ─────────────────────────────────────────────────────

    @Test
    void nonMemberCannotGenerate() throws Exception {
        api.post(outsider, base, "{\"title\":\"Test report\"}").andExpect(status().isNotFound());
    }

    @Test
    void memberWithoutManageProjectCannotGenerate() throws Exception {
        api.post(viewer, base, "{\"title\":\"Test report\"}").andExpect(status().isForbidden());
    }

    @Test
    void ownerWithManageProjectCanGenerate() throws Exception {
        api.post(owner, base, "{\"title\":\"Test report\"}").andExpect(status().isCreated());
    }

    @Test
    void generateRejectsBlankTitle() throws Exception {
        api.post(owner, base, "{\"title\":\"  \"}").andExpect(status().isBadRequest());
    }

    // ── list (GET) ──────────────────────────────────────────────────────────

    @Test
    void nonMemberCannotList() throws Exception {
        api.get(outsider, base).andExpect(status().isNotFound());
    }

    @Test
    void memberCanListWithoutManageProject() throws Exception {
        api.get(viewer, base).andExpect(status().isOk());
    }

    @Test
    void ownerCanList() throws Exception {
        api.get(owner, base).andExpect(status().isOk());
    }

    // ── download URL (GET /{id}/download) ───────────────────────────────────

    @Test
    void nonMemberCannotGetDownloadUrl() throws Exception {
        api.get(outsider, base + "/" + UUID.randomUUID() + "/download").andExpect(status().isNotFound());
    }

    @Test
    void memberGets404ForUnknownReport() throws Exception {
        api.get(viewer, base + "/" + UUID.randomUUID() + "/download").andExpect(status().isNotFound());
    }
}

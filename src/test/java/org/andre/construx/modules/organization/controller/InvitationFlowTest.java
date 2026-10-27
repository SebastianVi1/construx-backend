package org.andre.construx.modules.organization.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.service.InvitationMailer;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @MockitoBean InvitationMailer mailer;

    @Test
    void ownerInvitesAWorkerToASiteAndOnlyTheTokenHashIsStored() throws Exception {
        Fixture f = fixture();
        Project site = projects.save(new Project(organizations.findById(f.orgId).orElseThrow(), "Tower"));
        String email = "Bob-" + UUID.randomUUID() + "@Example.com";

        MvcResult result = invite(f, email, "WORKER", site.getId().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.projectId").value(site.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andReturn();

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(email.toLowerCase()), eq(f.orgName), token.capture());
        String stored = jdbc.queryForObject("select token_hash from organization_invitation where id = ?",
                String.class, UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id")));
        assertEquals(TokenService.sha256(token.getValue()), stored);
        assertNotEquals(token.getValue(), stored);
    }

    @Test
    void inviteWithoutProjectHasNullProjectAndIsListedAsPending() throws Exception {
        Fixture f = fixture();
        invite(f, "carol@example.com", "MEMBER", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").isEmpty());
        mvc.perform(get("/api/organizations/" + f.orgId + "/invitations").cookie(f.ownerAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("carol@example.com"))
                .andExpect(jsonPath("$[0].role.code").value("MEMBER"));
        mvc.perform(get("/api/organizations/" + f.orgId + "/invitations?status=ACCEPTED").cookie(f.ownerAccess))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void invitingTheSameEmailAgainRevokesTheOldOneAndReplacesIt() throws Exception {
        Fixture f = fixture();
        invite(f, "dave@example.com", "WORKER", null).andExpect(status().isCreated());
        invite(f, "dave@example.com", "MEMBER", null).andExpect(status().isCreated());
        mvc.perform(get("/api/organizations/" + f.orgId + "/invitations").cookie(f.ownerAccess))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role.code").value("MEMBER"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from organization_invitation where organization_id = ? and status = 'REVOKED'",
                Long.class, f.orgId));
    }

    @Test
    void revokeReturns204ThenConflictThenNotFoundForAnotherOrganization() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        String id = JsonPath.read(invite(f, "erin@example.com", "WORKER", null)
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(delete("/api/organizations/" + other.orgId + "/invitations/" + id)
                        .cookie(other.ownerAccess, other.csrf.cookie()).header(other.csrf.header(), other.csrf.token()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/organizations/" + f.orgId + "/invitations/" + id)
                        .cookie(f.ownerAccess, f.csrf.cookie()).header(f.csrf.header(), f.csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/organizations/" + f.orgId + "/invitations/" + id)
                        .cookie(f.ownerAccess, f.csrf.cookie()).header(f.csrf.header(), f.csrf.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void rulesRejectOwnerRoleBadEmailExistingMemberUnknownRoleAndForeignProject() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        Project foreign = projects.save(new Project(organizations.findById(other.orgId).orElseThrow(), "Foreign"));
        invite(f, "frank@example.com", "OWNER", null).andExpect(status().isBadRequest());
        invite(f, "not-an-email", "WORKER", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        invite(f, f.ownerEmail, "WORKER", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        invite(f, "gina@example.com", "NO_SUCH_ROLE", null).andExpect(status().isNotFound());
        invite(f, "gina@example.com", "WORKER", foreign.getId().toString()).andExpect(status().isNotFound());
        assertEquals(0L, jdbc.queryForObject("select count(*) from organization_invitation where organization_id = ?",
                Long.class, f.orgId));
        verifyNoInteractions(mailer);
    }

    @Test
    void onlyRolesWithManageMembersCanInviteAndOutsidersGetNotFound() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        UserAccount worker = account();
        addMember(f.orgId, worker, "WORKER");
        UserAccount admin = account();
        addMember(f.orgId, admin, "ADMINISTRATOR");
        Fixture asWorker = f.as(access(worker));
        Fixture asAdmin = f.as(access(admin));

        invite(asWorker, "hal@example.com", "WORKER", null).andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/" + f.orgId + "/invitations").cookie(asWorker.ownerAccess))
                .andExpect(status().isForbidden());
        invite(asAdmin, "ivy@example.com", "WORKER", null).andExpect(status().isCreated());
        invite(f.as(other.ownerAccess), "jon@example.com", "WORKER", null).andExpect(status().isNotFound());
        mvc.perform(get("/api/organizations/" + f.orgId + "/invitations").cookie(other.ownerAccess))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCannotInvite() throws Exception {
        Fixture f = fixture();
        mvc.perform(post("/api/organizations/" + f.orgId + "/invitations").cookie(f.csrf.cookie())
                        .header(f.csrf.header(), f.csrf.token()).contentType("application/json")
                        .content("{\"email\":\"x@example.com\",\"roleCode\":\"WORKER\"}"))
                .andExpect(status().isUnauthorized());
    }

    // helpers

    private record Fixture(UUID orgId, String orgName, Cookie ownerAccess, String ownerEmail, Csrf csrf) {
        Fixture as(Cookie access) {
            return new Fixture(orgId, orgName, access, ownerEmail, csrf);
        }
    }

    private Fixture fixture() throws Exception {
        UserAccount owner = account();
        Cookie access = access(owner);
        Csrf csrf = csrf();
        String name = "Acme " + UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        return new Fixture(id, name, access, owner.getEmail(), csrf);
    }

    private ResultActions invite(Fixture f, String email, String roleCode, String projectId) throws Exception {
        String project = projectId == null ? "" : ",\"projectId\":\"" + projectId + "\"";
        return mvc.perform(post("/api/organizations/" + f.orgId + "/invitations")
                .cookie(f.ownerAccess, f.csrf.cookie()).header(f.csrf.header(), f.csrf.token())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"roleCode\":\"" + roleCode + "\"" + project + "}"));
    }

    private void addMember(UUID orgId, UserAccount user, String roleCode) {
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            Organization org = organizations.findById(orgId).orElseThrow();
            OrganizationRole role = org.getRoles().stream().filter(r -> r.getCode().equals(roleCode)).findFirst().orElseThrow();
            memberships.save(new OrganizationMembership(org, user, role));
        });
    }

    private UserAccount account() {
        UserAccount user = new UserAccount(UUID.randomUUID() + "@example.test", "test-hash");
        user.verifyEmail();
        return users.save(user);
    }

    private Cookie access(UserAccount user) {
        return new Cookie("access_token", tokens.start(user).access());
    }

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                JsonPath.read(body, "$.headerName"), JsonPath.read(body, "$.token"));
    }

    private record Csrf(Cookie cookie, String header, String token) {}
}

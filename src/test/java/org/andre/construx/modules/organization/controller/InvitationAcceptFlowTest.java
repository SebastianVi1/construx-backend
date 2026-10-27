package org.andre.construx.modules.organization.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.InvitationMailer;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.shared.security.DomainAuthorization;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationAcceptFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired DomainAuthorization authorization;
    @MockitoBean InvitationMailer mailer;

    @Test
    void invitedWorkerJoinsTheOrganizationAndTheSiteInOneStep() throws Exception {
        Org org = org();
        Project site = projects.save(new Project(organizations.findById(org.id).orElseThrow(), "Tower"));
        UserAccount bob = account();
        String token = invite(org, bob.getEmail(), "WORKER", site.getId());

        accept(access(bob), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.id").value(org.id.toString()))
                .andExpect(jsonPath("$.organization.name").value(org.name))
                .andExpect(jsonPath("$.role.code").value("WORKER"))
                .andExpect(jsonPath("$.projectId").value(site.getId().toString()))
                .andExpect(jsonPath("$.membershipId").isNotEmpty());

        assertEquals("ACTIVE", jdbc.queryForObject("select status from organization_membership "
                + "where organization_id = ? and user_account_id = ?", String.class, org.id, bob.getId()));
        assertEquals(1L, jdbc.queryForObject("select count(*) from project_access where project_id = ?",
                Long.class, site.getId()));
        assertEquals("ACCEPTED", jdbc.queryForObject("select status from organization_invitation "
                + "where token_hash = ?", String.class, TokenService.sha256(token)));
        // the worker's real permissions now work: sees the assigned site, cannot manage members
        assertDoesNotThrow(() -> authorization.require(bob.getId(), org.id, site.getId(), null, PermissionAction.VIEW_PROJECT));
        assertThrows(AccessDeniedException.class,
                () -> authorization.require(bob.getId(), org.id, null, null, PermissionAction.MANAGE_MEMBERS));
    }

    @Test
    void invitationWithoutSiteCreatesNoProjectAccess() throws Exception {
        Org org = org();
        UserAccount carol = account();
        String token = invite(org, carol.getEmail(), "MEMBER", null);
        accept(access(carol), token).andExpect(status().isOk()).andExpect(jsonPath("$.projectId").isEmpty());
        assertEquals(0L, jdbc.queryForObject("select count(*) from project_access pa join project p on p.id = pa.project_id "
                + "where p.organization_id = ?", Long.class, org.id));
    }

    @Test
    void secondAcceptOfTheSameTokenConflicts() throws Exception {
        Org org = org();
        UserAccount dave = account();
        String token = invite(org, dave.getEmail(), "WORKER", null);
        accept(access(dave), token).andExpect(status().isOk());
        accept(access(dave), token).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void wrongAccountUnknownTokenAndBlankTokenDoNotRevealOrJoin() throws Exception {
        Org org = org();
        UserAccount erin = account();
        UserAccount mallory = account();
        String token = invite(org, erin.getEmail(), "WORKER", null);

        accept(access(mallory), token).andExpect(status().isNotFound());
        accept(access(mallory), "no-such-token").andExpect(status().isNotFound());
        accept(access(mallory), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from organization_membership where user_account_id = ?",
                Long.class, mallory.getId()));
        // the real invitee can still use it afterwards
        accept(access(erin), token).andExpect(status().isOk());
    }

    @Test
    void expiredAndRevokedInvitationsConflict() throws Exception {
        Org org = org();
        UserAccount frank = account();
        UserAccount gina = account();
        String expired = invite(org, frank.getEmail(), "WORKER", null);
        jdbc.update("update organization_invitation set expires_at = ? where token_hash = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), TokenService.sha256(expired));
        accept(access(frank), expired).andExpect(status().isConflict());

        String revoked = invite(org, gina.getEmail(), "WORKER", null);
        jdbc.update("update organization_invitation set status = 'REVOKED' where token_hash = ?", TokenService.sha256(revoked));
        accept(access(gina), revoked).andExpect(status().isConflict());
        assertEquals(0L, jdbc.queryForObject("select count(*) from organization_membership where user_account_id in (?, ?)",
                Long.class, frank.getId(), gina.getId()));
    }

    @Test
    void anExistingMemberCannotAcceptAgainAndAnonymousIsRejected() throws Exception {
        Org org = org();
        UserAccount hal = account();
        String token = invite(org, hal.getEmail(), "WORKER", null);
        // hal became a member by another path after the invitation was issued
        jdbc.update("insert into organization_membership (id, organization_id, role_id, user_account_id, status) "
                        + "select ?, ?, id, ?, 'ACTIVE' from organization_role where organization_id = ? and code = 'MEMBER'",
                UUID.randomUUID(), org.id, hal.getId(), org.id);
        accept(access(hal), token).andExpect(status().isConflict());
        Csrf anonymous = csrf();
        mvc.perform(post("/api/invitations/accept").cookie(anonymous.cookie())
                        .header(anonymous.header(), anonymous.token()).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doubleSubmitCreatesExactlyOneMembership() throws Exception {
        Org org = org();
        UserAccount ivy = account();
        String token = invite(org, ivy.getEmail(), "WORKER", null);
        Cookie access = access(ivy);
        Csrf csrf = csrf();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> mvc.perform(post("/api/invitations/accept").cookie(access, csrf.cookie())
                    .header(csrf.header(), csrf.token()).contentType("application/json")
                    .content("{\"token\":\"" + token + "\"}")).andReturn().getResponse().getStatus();
            List<Future<Integer>> results = pool.invokeAll(List.of(call, call));
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertTrue(statuses.contains(200), statuses.toString());
            assertTrue(statuses.contains(409), statuses.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, jdbc.queryForObject("select count(*) from organization_membership "
                + "where organization_id = ? and user_account_id = ?", Long.class, org.id, ivy.getId()));
    }

    // helpers

    private record Org(UUID id, String name, Cookie ownerAccess, Csrf csrf) {}

    private Org org() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        String name = "Acme " + UUID.randomUUID();
        MvcResult created = mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return new Org(UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id")),
                name, access, csrf);
    }

    /** Invites through the real endpoint and returns the raw token the mailer was given. */
    private String invite(Org org, String email, String roleCode, UUID projectId) throws Exception {
        String project = projectId == null ? "" : ",\"projectId\":\"" + projectId + "\"";
        mvc.perform(post("/api/organizations/" + org.id + "/invitations").cookie(org.ownerAccess, org.csrf.cookie())
                        .header(org.csrf.header(), org.csrf.token()).contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"roleCode\":\"" + roleCode + "\"" + project + "}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).send(eq(email.toLowerCase()), eq(org.name), token.capture());
        return token.getValue();
    }

    private ResultActions accept(Cookie access, String token) throws Exception {
        Csrf csrf = csrf();
        return mvc.perform(post("/api/invitations/accept").cookie(access, csrf.cookie())
                .header(csrf.header(), csrf.token()).contentType("application/json")
                .content("{\"token\":\"" + token + "\"}"));
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

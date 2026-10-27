package org.andre.construx.modules.organization.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrganizationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void creatorOwnsNewOrganizationAndSeesItInTheirList() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        String name = "Acme " + UUID.randomUUID();

        MvcResult created = mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"  " + name + "  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/organizations").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/organizations/" + id).cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
        assertEquals(1L, jdbc.queryForObject("select count(*) from organization_membership where organization_id = ?",
                Long.class, UUID.fromString(id)));
    }

    @Test
    void ownerCanRenameOrganization() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        String id = createOrganization(access, csrf, "Before");

        mvc.perform(patch("/api/organizations/" + id).cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"After\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"));
        mvc.perform(get("/api/organizations/" + id).cookie(access))
                .andExpect(jsonPath("$.name").value("After"));
    }

    @Test
    void nonMembersGetNotFoundForReadAndRename() throws Exception {
        Cookie owner = access(account());
        Cookie stranger = access(account());
        Csrf csrf = csrf();
        String id = createOrganization(owner, csrf, "Private");

        mvc.perform(get("/api/organizations/" + id).cookie(stranger)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/organizations/" + id).cookie(stranger, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/organizations").cookie(stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/organizations/" + id).cookie(owner))
                .andExpect(jsonPath("$.name").value("Private"));
    }

    @Test
    void invalidNamesReturnValidationErrors() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name").isArray());
        mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"" + "x".repeat(161) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/organizations").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json").content("{\"name\":\"Acme\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String createOrganization(Cookie access, Csrf csrf, String name) throws Exception {
        MvcResult created = mvc.perform(post("/api/organizations").cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
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

package org.andre.construx.modules.identity.controller;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newUserGetsAllNullBodyNotNotFound() throws Exception {
        mvc.perform(get("/api/onboarding").cookie(access(account())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").isEmpty())
                .andExpect(jsonPath("$.businessType").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isEmpty());
    }

    @Test
    void putIsAnIdempotentUpsertThatCanChangeTheAnswerAndGrantsNothing() throws Exception {
        UserAccount user = account();
        Cookie access = access(user);
        Csrf csrf = csrf();
        long organizationsBefore = jdbc.queryForObject("select count(*) from organization", Long.class);
        long membershipsBefore = jdbc.queryForObject("select count(*) from organization_membership", Long.class);

        save(access, csrf, "{\"intent\":\"RUN_COMPANY\",\"businessType\":\"GENERAL_CONTRACTOR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("RUN_COMPANY"))
                .andExpect(jsonPath("$.businessType").value("GENERAL_CONTRACTOR"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        save(access, csrf, "{\"intent\":\"WORKER_OR_INVITED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("WORKER_OR_INVITED"))
                .andExpect(jsonPath("$.businessType").isEmpty());
        mvc.perform(get("/api/onboarding").cookie(access))
                .andExpect(jsonPath("$.intent").value("WORKER_OR_INVITED"));

        assertEquals(1, jdbc.queryForObject("select count(*) from user_onboarding where user_account_id = ?",
                Long.class, user.getId()));
        assertEquals(organizationsBefore, jdbc.queryForObject("select count(*) from organization", Long.class));
        assertEquals(membershipsBefore,
                jdbc.queryForObject("select count(*) from organization_membership", Long.class));
    }

    @Test
    void missingIntentIsAValidationErrorAndUnknownEnumIsMalformed() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        save(access, csrf, "{\"businessType\":\"ARCHITECT_ENGINEER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.intent").isArray());
        save(access, csrf, "{\"intent\":\"BE_KING\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }

    @Test
    void unauthenticatedAndOtherUsersPathsAreRejected() throws Exception {
        Csrf csrf = csrf();
        mvc.perform(get("/api/onboarding")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/onboarding").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json").content("{\"intent\":\"RUN_COMPANY\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/onboarding/" + UUID.randomUUID()).cookie(access(account())))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions save(Cookie access, Csrf csrf, String body) throws Exception {
        return mvc.perform(put("/api/onboarding").cookie(access, csrf.cookie()).header(csrf.header(), csrf.token())
                .contentType("application/json").content(body));
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

package org.andre.construx.modules.identity.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void existingAccountCanReadAndCompleteProfileWithoutOtherDomainRecords() throws Exception {
        UserAccount user = account();
        Cookie access = access(user);
        long organizationsBefore = jdbc.queryForObject("select count(*) from organization", Long.class);
        long membershipsBefore = jdbc.queryForObject("select count(*) from organization_membership", Long.class);
        long projectsBefore = jdbc.queryForObject("select count(*) from project", Long.class);
        assertTrue(profiles.findById(user.getId()).isEmpty());
        mvc.perform(get("/api/profile").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").isEmpty())
                .andExpect(jsonPath("$.phoneNumber").isEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        Csrf csrf = csrf();
        mvc.perform(put("/api/profile").cookie(access, csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content("{\"fullName\":\"  Ada Lovelace  \",\"phoneNumber\":\"  +442071838750  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.phoneNumber").value("+442071838750"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/profile").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"));
        mvc.perform(put("/api/profile").cookie(access, csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content("{\"fullName\":\"Ada Byron\",\"phoneNumber\":\"+14155552671\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Byron"));
        assertEquals(1, profiles.count());
        assertEquals("Ada Byron", profiles.findById(user.getId()).orElseThrow().getFullName());
        assertEquals(organizationsBefore, jdbc.queryForObject("select count(*) from organization", Long.class));
        assertEquals(membershipsBefore,
                jdbc.queryForObject("select count(*) from organization_membership", Long.class));
        assertEquals(projectsBefore, jdbc.queryForObject("select count(*) from project", Long.class));
    }

    @Test
    void deletingAccountRemovesItsProfile() {
        UserAccount user = account();
        profiles.save(new UserProfile(user, "Ada Lovelace", "+442071838750"));
        assertTrue(profiles.findById(user.getId()).isPresent());
        users.deleteById(user.getId());
        assertTrue(profiles.findById(user.getId()).isEmpty());
    }

    @Test
    void missingAndInvalidFieldsReturnConsistentValidationErrors() throws Exception {
        Cookie access = access(account());
        Csrf csrf = csrf();
        mvc.perform(put("/api/profile").cookie(access, csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content("{\"phoneNumber\":\"+14155552671\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.fullName").isArray());
        mvc.perform(put("/api/profile").cookie(access, csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content("{\"fullName\":\"Ada\",\"phoneNumber\":\"4155552671\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.phoneNumber").isArray());
    }

    @Test
    void unauthenticatedAndUuidTamperingCannotReadOrWriteProfiles() throws Exception {
        UserAccount owner = account();
        UserAccount other = account();
        Cookie access = access(owner);
        Csrf csrf = csrf();
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/profile").cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                        .contentType("application/json")
                        .content("{\"fullName\":\"Ada\",\"phoneNumber\":\"+14155552671\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile/" + other.getId()).cookie(access)).andExpect(status().isNotFound());
        mvc.perform(put("/api/profile/" + other.getId()).cookie(access, csrf.cookie())
                        .header(csrf.header(), csrf.token()).contentType("application/json")
                        .content("{\"fullName\":\"Ada\",\"phoneNumber\":\"+14155552671\"}"))
                .andExpect(status().isNotFound());
        assertTrue(profiles.findById(other.getId()).isEmpty());
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

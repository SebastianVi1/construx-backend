package org.andre.construx.modules.identity.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleLinkTest {

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void linkRequestRequiresAuthentication() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/auth/google/link").cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingLinkExpiresWithAShortLivedSession() throws Exception {
        String email = "link-" + UUID.randomUUID() + "@example.test";
        UserAccount user = new UserAccount(email, passwords.encode("long-password-here"));
        user.verifyEmail();
        users.save(user);
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        Cookie xsrf = csrf.getResponse().getCookie("XSRF-TOKEN");
        Cookie access = mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"long-password-here\"}"))
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("access_token");

        MvcResult link = mvc.perform(post("/api/auth/google/link").cookie(xsrf, access).header("X-XSRF-TOKEN", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google"))
                .andReturn();

        assertEquals(600, link.getRequest().getSession().getMaxInactiveInterval());
    }
}

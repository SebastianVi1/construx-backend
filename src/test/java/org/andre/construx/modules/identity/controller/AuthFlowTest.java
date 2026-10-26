package org.andre.construx.modules.identity.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    @MockitoBean JavaMailSender sender;

    @Test
    void registrationRejectsPasswordShorterThanTwelveCharacters() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        String csrf = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String headerName = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/auth/register").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"short-" + UUID.randomUUID() + "@example.test\",\"password\":\"12345678901\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationVerificationLoginRefreshAndLogout() throws Exception {
        String email = "person-" + UUID.randomUUID() + "@example.test";
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        String csrf = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String headerName = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie);

        mvc.perform(post("/api/auth/register").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"long-password-here\"}"))
                .andExpect(status().isAccepted());
        UserAccount user = users.findByEmail(email).orElseThrow();
        assertFalse(user.isEmailVerified());
        var mail = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        String verificationToken = mail.getValue().getText().split("token=")[1];

        mvc.perform(post("/api/auth/verify").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"token\":\"" + verificationToken + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/verify").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"token\":\"" + verificationToken + "\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult login = mvc.perform(post("/api/auth/login").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"long-password-here\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie access = login.getResponse().getCookie("access_token");
        Cookie refresh = login.getResponse().getCookie("refresh_token");
        assertTrue(access.isHttpOnly());
        assertTrue(refresh.isHttpOnly());
        mvc.perform(get("/api/auth/me").cookie(access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(post("/api/auth/google/link").cookie(access))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/google/link").cookie(csrfCookie, access)
                        .header(headerName, csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google"));

        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(csrfCookie, refresh)
                        .header(headerName, csrf))
                .andExpect(status().isNoContent()).andReturn();
        Cookie rotatedRefresh = rotated.getResponse().getCookie("refresh_token");
        assertNotEquals(refresh.getValue(), rotatedRefresh.getValue());
        mvc.perform(post("/api/auth/logout").cookie(csrfCookie, rotatedRefresh)
                        .header(headerName, csrf))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeRequestsRequireCsrfAndApiRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"a@example.test\",\"password\":\"whatever\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("accounts.google.com")));
    }

    @Test
    void passwordChangeRevokesExistingAccess() throws Exception {
        String email = "changed-" + UUID.randomUUID() + "@example.test";
        UserAccount user = new UserAccount(email, passwords.encode("old-password-123"));
        user.verifyEmail();
        users.save(user);
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String header = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mvc.perform(post("/api/auth/login").cookie(csrfCookie).header(header, token)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"old-password-123\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie access = login.getResponse().getCookie("access_token");
        mvc.perform(post("/api/auth/password").cookie(csrfCookie, access).header(header, token)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"new-password-456\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isUnauthorized());
    }
}

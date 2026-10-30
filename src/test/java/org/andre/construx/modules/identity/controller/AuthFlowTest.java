package org.andre.construx.modules.identity.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.PasswordReset;
import org.andre.construx.modules.identity.repository.PasswordResetRepository;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
    @Autowired PasswordResetRepository resets;
    @Autowired UserProfileRepository profiles;
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
    void registrationStoresTheSubmittedNameAsTheProfileFullName() throws Exception {
        String email = "named-" + UUID.randomUUID() + "@example.test";
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        String csrf = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        String headerName = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.headerName");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/auth/register").cookie(csrfCookie)
                        .header(headerName, csrf)
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"long-password-here\",\"name\":\" Ana López \"}"))
                .andExpect(status().isAccepted());
        UserAccount user = users.findByEmail(email).orElseThrow();
        var profile = profiles.findById(user.getId()).orElseThrow();
        assertEquals("Ana López", profile.getFullName());
        assertNull(profile.getPhoneNumber());
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

    private record Csrf(Cookie cookie, String header, String token) {}

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                JsonPath.read(body, "$.headerName"), JsonPath.read(body, "$.token"));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(Csrf csrf, String path, String json,
            Cookie... extra) throws Exception {
        var request = post(path).cookie(csrf.cookie()).header(csrf.header(), csrf.token())
                .contentType("application/json").content(json);
        if (extra.length > 0) request.cookie(extra);
        return mvc.perform(request);
    }

    private String verifiedAccount(String password) {
        String email = "recover-" + UUID.randomUUID() + "@example.test";
        UserAccount user = new UserAccount(email, passwords.encode(password));
        user.verifyEmail();
        users.save(user);
        return email;
    }

    private Cookie loginCookie(Csrf csrf, String email, String password) throws Exception {
        return postJson(csrf, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("access_token");
    }

    private List<String> mailedTokens(int expectedMails) {
        var mail = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(expectedMails)).send(mail.capture());
        return mail.getAllValues().stream().map(m -> m.getText().split("token=")[1].split("\\s")[0]).toList();
    }

    private void ageResetTokens(String email) {
        UserAccount user = users.findByEmail(email).orElseThrow();
        for (PasswordReset reset : resets.findAll()) {
            if (reset.getUser().getId().equals(user.getId()))
                org.springframework.test.util.ReflectionTestUtils.setField(reset, "createdAt",
                        Instant.now().minusSeconds(120));
            resets.save(reset);
        }
    }

    @Test
    void forgotAndResetReplaceThePasswordAndRevokeSessions() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.test";
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"long-password-here\"}")
                .andExpect(status().isAccepted());
        String verification = mailedTokens(1).get(0);
        postJson(csrf, "/api/auth/verify", "{\"token\":\"" + verification + "\"}").andExpect(status().isNoContent());
        Cookie oldSession = loginCookie(csrf, email, "long-password-here");
        mvc.perform(get("/api/auth/me").cookie(oldSession)).andExpect(status().isOk());

        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"" + email + "\"}")
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        String resetToken = mailedTokens(2).get(1);

        var reset = postJson(csrf, "/api/auth/password/reset",
                "{\"token\":\"" + resetToken + "\",\"newPassword\":\"brand-new-password\"}")
                .andExpect(status().isNoContent()).andExpect(content().string("")).andReturn();
        assertEquals(0, reset.getResponse().getCookie("access_token").getMaxAge());
        assertEquals(0, reset.getResponse().getCookie("refresh_token").getMaxAge());

        postJson(csrf, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"long-password-here\"}")
                .andExpect(status().isUnauthorized());
        loginCookie(csrf, email, "brand-new-password");
        mvc.perform(get("/api/auth/me").cookie(oldSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgotForAnUnknownEmailLooksIdenticalAndSendsNothing() throws Exception {
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"ghost-" + UUID.randomUUID() + "@example.test\"}")
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    @Test
    void forgotRejectsAMalformedOrBlankEmail() throws Exception {
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void aResetTokenWorksOnlyOnce() throws Exception {
        String email = verifiedAccount("old-password-123");
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        String token = mailedTokens(1).get(0);
        String body = "{\"token\":\"" + token + "\",\"newPassword\":\"brand-new-password\"}";
        postJson(csrf, "/api/auth/password/reset", body).andExpect(status().isNoContent());
        postJson(csrf, "/api/auth/password/reset", body).andExpect(status().isUnauthorized());
    }

    @Test
    void resetValidatesPasswordLengthAndRequiresAToken() throws Exception {
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/password/reset", "{\"token\":\"t\",\"newPassword\":\"" + "a".repeat(11) + "\"}")
                .andExpect(status().isBadRequest());
        postJson(csrf, "/api/auth/password/reset", "{\"token\":\"t\",\"newPassword\":\"" + "a".repeat(73) + "\"}")
                .andExpect(status().isBadRequest());
        postJson(csrf, "/api/auth/password/reset", "{\"token\":\" \",\"newPassword\":\"brand-new-password\"}")
                .andExpect(status().isBadRequest());
        postJson(csrf, "/api/auth/password/reset", "{\"token\":\"unknown\",\"newPassword\":\"brand-new-password\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondForgotInvalidatesTheFirstLink() throws Exception {
        String email = verifiedAccount("old-password-123");
        Csrf csrf = csrf();
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        ageResetTokens(email);
        postJson(csrf, "/api/auth/password/forgot", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        List<String> tokens = mailedTokens(2);
        postJson(csrf, "/api/auth/password/reset",
                "{\"token\":\"" + tokens.get(0) + "\",\"newPassword\":\"brand-new-password\"}")
                .andExpect(status().isUnauthorized());
        postJson(csrf, "/api/auth/password/reset",
                "{\"token\":\"" + tokens.get(1) + "\",\"newPassword\":\"brand-new-password\"}")
                .andExpect(status().isNoContent());
    }
}

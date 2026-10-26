package org.andre.construx.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndpointAccessTest {

    @Autowired MockMvc mvc;

    @Test
    void healthProbeIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsPasswordsBeyondTheBcryptLimit() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/auth/register").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"email\":\"long@example.test\",\"password\":\"" + "a".repeat(73) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void passwordRecoveryEndpointsArePublicButPasswordChangeStillNeedsAuthentication() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/auth/password/forgot").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .contentType("application/json").content("{\"email\":\"nobody@example.test\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/password/reset").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"token\":\"unknown\",\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/auth/password").cookie(cookie).header("X-XSRF-TOKEN", token)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"old-password-123\",\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isUnauthorized());
    }
}

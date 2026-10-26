package org.andre.construx.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.security.rate-limit.max-requests=3",
        "app.security.rate-limit.window-seconds=60"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitTest {

    @Autowired MockMvc mvc;

    @Test
    void loginIsThrottledAfterTheLimitWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void eachSensitiveEndpointHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/resend").contentType("application/json").content("{}"));
        }
        mvc.perform(post("/api/auth/resend").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/auth/verify").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyEndpointsAreNotThrottled() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        }
    }

    @Test
    void passwordRecoveryEndpointsAreThrottledWithTheirOwnBudgets() throws Exception {
        for (String path : new String[] {"/api/auth/password/forgot", "/api/auth/password/reset"}) {
            for (int i = 0; i < 3; i++) {
                mvc.perform(post(path).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));
        }
    }
}

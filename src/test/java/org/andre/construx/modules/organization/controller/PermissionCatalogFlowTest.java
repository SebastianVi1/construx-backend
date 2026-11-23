package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.support.Api;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionCatalogFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;

    @Test
    void catalogIsServedToAnyAuthenticatedUser() throws Exception {
        Api api = new Api(mvc, users, tokens);
        api.get(api.signUp(), "/api/permissions/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[*].key").value(contains(
                        "COMPANY", "SITES", "PHASES", "TASKS", "PRICES", "BUDGETS", "PLANS")))
                .andExpect(jsonPath("$.groups[6].names.es").value("Planos"))
                .andExpect(jsonPath("$.actions.length()").value(16))
                .andExpect(jsonPath("$.actions[15].action").value("MANAGE_PLAN"))
                .andExpect(jsonPath("$.actions[15].group").value("PLANS"))
                .andExpect(jsonPath("$.actions[15].names.en").value("Edit plans"))
                .andExpect(jsonPath("$.actions[15].descriptions.es").isNotEmpty())
                .andExpect(jsonPath("$.actions[15].scopes").value(contains("PROJECT", "ORGANIZATION")))
                .andExpect(jsonPath("$.actions[15].requires[0].action").value("VIEW_PLAN"))
                .andExpect(jsonPath("$.actions[15].requires[0].minScope").value("SAME"))
                .andExpect(jsonPath("$.actions[15].requires[1].action").value("VIEW_PROJECT"))
                .andExpect(jsonPath("$.actions[15].requires[1].minScope").value("PROJECT"))
                .andExpect(jsonPath("$.actions[?(@.action == 'MANAGE_ACTIVITY')].scopes[0]").value(hasItem("OWN")))
                .andExpect(jsonPath("$.actions[?(@.action == 'MANAGE_ROLES')].requires.length()").value(hasItem(0)));
    }

    @Test
    void catalogNeedsAuthentication() throws Exception {
        mvc.perform(get("/api/permissions/catalog")).andExpect(status().isUnauthorized());
    }
}

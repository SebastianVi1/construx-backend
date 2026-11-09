package org.andre.construx.shared.demo;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo profile seeds a working demo company through the domain services, once. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-seed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "construx.demo.password=Seed-Pass-123"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
class DemoSeederTest {
    static final String COMPANY = "Constructora del Norte S.A.";

    @Autowired MockMvc mvc;
    @Autowired DemoSeeder seeder;

    @Test
    void demoOwnerSignsInAndSeesTheCompanyWithItsThreeSites() throws Exception {
        Cookie access = login(DemoSeeder.OWNER_EMAIL, "Seed-Pass-123");

        List<String> orgIds = JsonPath.read(body(access, "/api/organizations"), "$[?(@.name == '" + COMPANY + "')].id");
        assertThat(orgIds).hasSize(1);
        List<String> sites = JsonPath.read(body(access, "/api/organizations/" + orgIds.get(0) + "/projects"), "$[*].name");
        assertThat(sites).containsExactlyInAnyOrder(
                "Torre Residencial Norte", "Centro Comercial Belgrano", "Complejo Las Acacias");
    }

    @Test
    void seedingAgainChangesNothing() throws Exception {
        Cookie access = login(DemoSeeder.OWNER_EMAIL, "Seed-Pass-123");
        String orgsBefore = body(access, "/api/organizations");

        seeder.seed();

        assertThat(body(access, "/api/organizations")).isEqualTo(orgsBefore);
        List<String> orgIds = JsonPath.read(orgsBefore, "$[?(@.name == '" + COMPANY + "')].id");
        List<String> sites = JsonPath.read(body(access, "/api/organizations/" + orgIds.get(0) + "/projects"), "$[*].id");
        assertThat(sites).hasSize(3);
    }

    @Test
    void demoStaffSignInWithTheConfiguredPassword() throws Exception {
        Cookie access = login("carmen.ruiz@demo.construx", "Seed-Pass-123");

        List<String> names = JsonPath.read(body(access, "/api/organizations"), "$[*].name");
        assertThat(names).containsExactly(COMPANY);
    }

    private Cookie login(String email, String password) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        String csrfBody = csrf.getResponse().getContentAsString();
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN"))
                        .header(JsonPath.read(csrfBody, "$.headerName"), (String) JsonPath.read(csrfBody, "$.token"))
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isNoContent()).andReturn();
        return result.getResponse().getCookie("access_token");
    }

    private String body(Cookie access, String path) throws Exception {
        return mvc.perform(get(path).cookie(access)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}

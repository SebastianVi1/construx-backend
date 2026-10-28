package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String suppliers;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        suppliers = "/api/organizations/" + orgId + "/suppliers";
    }

    private String create(String json) throws Exception {
        return Api.id(api.post(owner, suppliers, json).andExpect(status().isCreated()));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId);
    }

    @Test
    void createReturnsTheCleanedSupplierAndTheListIsOrderedByName() throws Exception {
        String id = Api.id(api.post(owner, suppliers,
                        "{\"name\":\" Home   Depot \",\"branch\":\" Periférico Sur \",\"website\":\"https://www.homedepot.com.mx\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Home Depot"))
                .andExpect(jsonPath("$.branch").value("Periférico Sur"))
                .andExpect(jsonPath("$.website").value("https://www.homedepot.com.mx"))
                .andExpect(jsonPath("$.archivedAt").value(nullValue())));
        Map<String, Object> row = jdbc.queryForMap("select * from supplier where id = ?", UUID.fromString(id));
        assertEquals(orgId, row.get("organization_id"));
        assertEquals("Home Depot", row.get("name"));
        assertEquals("Periférico Sur", row.get("branch"));
        create("{\"name\":\"Ferretería Lupita\"}");
        api.get(owner, suppliers).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Ferretería Lupita"))
                .andExpect(jsonPath("$[0].branch").value(nullValue()))
                .andExpect(jsonPath("$[1].name").value("Home Depot"));
    }

    @Test
    void duplicateNameAndBranchIgnoringCaseAndWhitespaceIsAConflict() throws Exception {
        create("{\"name\":\"Home Depot\",\"branch\":\"Periférico Sur\"}");
        api.post(owner, suppliers, "{\"name\":\"home  DEPOT\",\"branch\":\" periférico sur\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"))
                .andExpect(jsonPath("$.detail").value("A supplier with this name and branch already exists"));
        create("{\"name\":\"Home Depot\",\"branch\":\"Satélite\"}");
        String plain = create("{\"name\":\"Home Depot\"}");
        api.patch(owner, suppliers + "/" + plain, "{\"branch\":\"PERIFÉRICO SUR\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        assertEquals(3, count());
    }

    @Test
    void websiteMustBeHttpOrHttps() throws Exception {
        api.post(owner, suppliers, "{\"name\":\"A\",\"website\":\"ftp://a.mx\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, suppliers, "{\"name\":\"A\",\"website\":\"a.mx\"}").andExpect(status().isBadRequest());
        api.post(owner, suppliers, "{\"name\":\" \"}").andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void patchChangesOnlyTheFieldsSentAndEmptyStringsClearOptionals() throws Exception {
        String id = create("{\"name\":\"A\",\"branch\":\"Centro\",\"website\":\"https://a.mx\"}");
        api.patch(owner, suppliers + "/" + id, "{\"name\":\"B\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("B"))
                .andExpect(jsonPath("$.branch").value("Centro"))
                .andExpect(jsonPath("$.website").value("https://a.mx"));
        api.patch(owner, suppliers + "/" + id, "{\"branch\":\"\",\"website\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value(nullValue()))
                .andExpect(jsonPath("$.website").value(nullValue()));
        Map<String, Object> row = jdbc.queryForMap("select * from supplier where id = ?", UUID.fromString(id));
        assertEquals("B", row.get("name"));
        assertEquals(null, row.get("branch"));
        assertEquals(null, row.get("website"));
    }

    @Test
    void archivedSuppliersLeaveTheList() throws Exception {
        String id = create("{\"name\":\"Closed\"}");
        create("{\"name\":\"Open\"}");
        api.post(owner, suppliers + "/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertNotNull(jdbc.queryForObject("select archived_at from supplier where id = ?", Object.class,
                UUID.fromString(id)));
        api.get(owner, suppliers).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Open"));
        api.get(owner, suppliers + "?includeArchived=true").andExpect(jsonPath("$.length()").value(2));
        assertEquals(2, count());
    }

    @Test
    void otherTenantsGetNotFound() throws Exception {
        String id = create("{\"name\":\"Mine\"}");
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String theirs = "/api/organizations/" + otherOrg + "/suppliers";
        api.get(stranger, suppliers).andExpect(status().isNotFound());
        api.patch(stranger, theirs + "/" + id, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        api.post(stranger, theirs + "/" + id + "/archive", null).andExpect(status().isNotFound());
        api.get(stranger, theirs).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertEquals("Mine", jdbc.queryForObject("select name from supplier where id = ?", String.class,
                UUID.fromString(id)));
    }
}

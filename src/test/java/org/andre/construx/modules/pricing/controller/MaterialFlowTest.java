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
class MaterialFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String materials;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        materials = "/api/organizations/" + orgId + "/materials";
    }

    private String create(String json) throws Exception {
        return Api.id(api.post(owner, materials, json).andExpect(status().isCreated()));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from material where organization_id = ?", Long.class, orgId);
    }

    @Test
    void createReturnsTheCleanedMaterialAndPersistsIt() throws Exception {
        String id = Api.id(api.post(owner, materials,
                        "{\"name\":\"  Cemento   gris \",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\",\"brand\":\" Cemex \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Cemento gris"))
                .andExpect(jsonPath("$.unit").value("BAG"))
                .andExpect(jsonPath("$.category").value("CEMENT_AGGREGATES"))
                .andExpect(jsonPath("$.brand").value("Cemex"))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.latestPrices.length()").value(0)));
        Map<String, Object> row = jdbc.queryForMap("select * from material where id = ?", UUID.fromString(id));
        assertEquals(orgId, row.get("organization_id"));
        assertEquals("Cemento gris", row.get("name"));
        assertEquals("BAG", row.get("unit"));
        assertEquals("CEMENT_AGGREGATES", row.get("category"));
        assertEquals("Cemex", row.get("brand"));
        assertNotNull(row.get("normalized_key"));
        api.get(owner, materials).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void duplicateNameUnitAndBrandIgnoringCaseAndWhitespaceIsAConflict() throws Exception {
        create("{\"name\":\"Cemento gris\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\",\"brand\":\"Cemex\"}");
        api.post(owner, materials,
                        "{\"name\":\"  CEMENTO   Gris\",\"unit\":\"BAG\",\"category\":\"FINISHES\",\"brand\":\" cemex \"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL"))
                .andExpect(jsonPath("$.detail").value("A material with this name, unit and brand already exists"));
        assertEquals(1, count());
        create("{\"name\":\"Cemento gris\",\"unit\":\"KILOGRAM\",\"category\":\"CEMENT_AGGREGATES\",\"brand\":\"Cemex\"}");
        create("{\"name\":\"Cemento gris\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\"}");
        assertEquals(3, count());
    }

    @Test
    void renamingIntoAnExistingMaterialIsAConflictAndChangesNothing() throws Exception {
        create("{\"name\":\"Arena\",\"unit\":\"CUBIC_METER\",\"category\":\"CEMENT_AGGREGATES\"}");
        String grava = create("{\"name\":\"Grava\",\"unit\":\"CUBIC_METER\",\"category\":\"CEMENT_AGGREGATES\"}");
        api.patch(owner, materials + "/" + grava, "{\"name\":\" arena \"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL"));
        assertEquals("Grava", jdbc.queryForObject("select name from material where id = ?", String.class,
                UUID.fromString(grava)));
        api.patch(owner, materials + "/" + grava, "{\"name\":\"Grava\"}").andExpect(status().isOk());
    }

    @Test
    void patchChangesOnlyTheFieldsSentAndAnEmptyBrandClearsIt() throws Exception {
        String id = create("{\"name\":\"Varilla\",\"unit\":\"PIECE\",\"category\":\"STEEL\",\"brand\":\"Deacero\"}");
        api.patch(owner, materials + "/" + id, "{\"name\":\"Varilla 3/8\",\"unit\":\"METER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Varilla 3/8"))
                .andExpect(jsonPath("$.unit").value("METER"))
                .andExpect(jsonPath("$.category").value("STEEL"))
                .andExpect(jsonPath("$.brand").value("Deacero"));
        api.patch(owner, materials + "/" + id, "{\"brand\":\"\",\"category\":\"FINISHES\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brand").value(nullValue()))
                .andExpect(jsonPath("$.category").value("FINISHES"));
        Map<String, Object> row = jdbc.queryForMap("select * from material where id = ?", UUID.fromString(id));
        assertEquals("Varilla 3/8", row.get("name"));
        assertEquals("METER", row.get("unit"));
        assertEquals(null, row.get("brand"));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        api.post(owner, materials, "{\"name\":\" \",\"unit\":\"BAG\",\"category\":\"STEEL\"}")
                .andExpect(status().isBadRequest());
        api.post(owner, materials, "{\"name\":\"X\",\"category\":\"STEEL\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, materials, "{\"name\":\"X\",\"unit\":\"BAG\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, materials, "{\"name\":\"X\",\"unit\":\"TON\",\"category\":\"STEEL\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
        api.post(owner, materials, "{\"name\":\"X\",\"unit\":\"BAG\",\"category\":\"FOOD\"}")
                .andExpect(status().isBadRequest());
        api.get(owner, materials + "?category=FOOD").andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void listIsOrderedByNameAndFiltersBySearchAndCategory() throws Exception {
        create("{\"name\":\"Varilla\",\"unit\":\"PIECE\",\"category\":\"STEEL\",\"brand\":\"Deacero\"}");
        create("{\"name\":\"Cemento gris\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\",\"brand\":\"Cemex\"}");
        create("{\"name\":\"Alambre recocido\",\"unit\":\"KILOGRAM\",\"category\":\"STEEL\"}");
        api.get(owner, materials).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Alambre recocido"))
                .andExpect(jsonPath("$[1].name").value("Cemento gris"))
                .andExpect(jsonPath("$[2].name").value("Varilla"));
        api.get(owner, materials + "?category=STEEL").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alambre recocido"));
        api.get(owner, materials + "?query=  CEMENTO  ").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Cemento gris"));
        api.get(owner, materials + "?query=deacero").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Varilla"));
        api.get(owner, materials + "?query=cemento&category=STEEL").andExpect(jsonPath("$.length()").value(0));
        api.get(owner, materials + "?query=100%").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void archivedMaterialsLeaveTheListButKeepTheirDetail() throws Exception {
        String id = create("{\"name\":\"Old\",\"unit\":\"BAG\",\"category\":\"STEEL\"}");
        create("{\"name\":\"Current\",\"unit\":\"BAG\",\"category\":\"STEEL\"}");
        api.post(owner, materials + "/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertNotNull(jdbc.queryForObject("select archived_at from material where id = ?", Object.class,
                UUID.fromString(id)));
        api.get(owner, materials).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Current"));
        api.get(owner, materials + "?includeArchived=true").andExpect(jsonPath("$.length()").value(2));
        api.get(owner, materials + "/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.history.length()").value(0));
        api.post(owner, materials + "/" + id + "/archive", null).andExpect(status().isOk());
        assertEquals(2, count());
    }

    @Test
    void otherTenantsGetNotFound() throws Exception {
        String id = create("{\"name\":\"Cemento\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\"}");
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String theirs = "/api/organizations/" + otherOrg + "/materials";
        api.get(stranger, materials).andExpect(status().isNotFound());
        api.get(stranger, materials + "/" + id).andExpect(status().isNotFound());
        api.get(stranger, theirs + "/" + id).andExpect(status().isNotFound());
        api.patch(stranger, theirs + "/" + id, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        api.post(stranger, theirs + "/" + id + "/archive", null).andExpect(status().isNotFound());
        api.get(owner, materials + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        assertEquals("Cemento", jdbc.queryForObject("select name from material where id = ?", String.class,
                UUID.fromString(id)));
    }
}

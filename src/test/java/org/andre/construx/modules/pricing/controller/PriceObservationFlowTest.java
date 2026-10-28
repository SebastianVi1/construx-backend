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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceObservationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String base;
    private String observations;
    private String cement;
    private String depot;
    private String lupita;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        observations = base + "/price-observations";
        cement = Api.id(api.post(owner, base + "/materials",
                "{\"name\":\"Cemento\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\"}").andExpect(status().isCreated()));
        depot = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Home Depot\",\"branch\":\"Sur\"}")
                .andExpect(status().isCreated()));
        lupita = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Lupita\"}").andExpect(status().isCreated()));
    }

    private String body(String material, String supplier, String price, String observedOn) {
        return "{\"materialId\":\"" + material + "\",\"supplierId\":\"" + supplier + "\",\"price\":" + price
                + ",\"observedOn\":\"" + observedOn + "\"}";
    }

    private String record(String supplier, String price, String observedOn) throws Exception {
        return Api.id(api.post(owner, observations, body(cement, supplier, price, observedOn))
                .andExpect(status().isCreated()));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from price_observation where organization_id = ?", Long.class, orgId);
    }

    @Test
    void recordingReturnsTheObservationWithMoneyAsAStringAndPersistsIt() throws Exception {
        String id = Api.id(api.post(owner, observations,
                        "{\"materialId\":\"" + cement + "\",\"supplierId\":\"" + depot
                                + "\",\"price\":\"245.5\",\"observedOn\":\"2026-10-01\",\"sku\":\" 100-200 \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.materialId").value(cement))
                .andExpect(jsonPath("$.supplierId").value(depot))
                .andExpect(jsonPath("$.supplierName").value("Home Depot"))
                .andExpect(jsonPath("$.supplierBranch").value("Sur"))
                .andExpect(jsonPath("$.price").value("245.50"))
                .andExpect(jsonPath("$.observedOn").value("2026-10-01"))
                .andExpect(jsonPath("$.sku").value("100-200"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty()));
        Map<String, Object> row = jdbc.queryForMap("select * from price_observation where id = ?", UUID.fromString(id));
        assertEquals(orgId, row.get("organization_id"));
        assertEquals(UUID.fromString(cement), row.get("material_id"));
        assertEquals(UUID.fromString(depot), row.get("supplier_id"));
        assertEquals(0, new BigDecimal("245.50").compareTo((BigDecimal) row.get("price")));
        assertEquals(Date.valueOf("2026-10-01"), row.get("observed_on"));
        assertEquals("MANUAL", row.get("source"));
        assertEquals("100-200", row.get("sku"));
        record(lupita, "99", "2026-10-01");
        api.post(owner, observations, "{\"materialId\":\"" + cement + "\",\"supplierId\":\"" + depot
                        + "\",\"price\":\"1\",\"observedOn\":\"2026-10-01\",\"source\":\"CSV\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("MANUAL"));
    }

    @Test
    void listShowsTheLatestPricePerSupplierAndMarksTheCheapest() throws Exception {
        record(depot, "100", "2026-10-01");
        record(depot, "90.5", "2026-10-05");
        record(depot, "120", "2026-09-30");
        record(lupita, "95", "2026-10-05");
        record(lupita, "80", "2026-10-01");
        api.get(owner, base + "/materials").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latestPrices.length()").value(2))
                .andExpect(jsonPath("$[0].latestPrices[0].supplierId").value(depot))
                .andExpect(jsonPath("$[0].latestPrices[0].price").value("90.50"))
                .andExpect(jsonPath("$[0].latestPrices[0].observedOn").value("2026-10-05"))
                .andExpect(jsonPath("$[0].latestPrices[0].cheapest").value(true))
                .andExpect(jsonPath("$[0].latestPrices[1].supplierId").value(lupita))
                .andExpect(jsonPath("$[0].latestPrices[1].price").value("95.00"))
                .andExpect(jsonPath("$[0].latestPrices[1].cheapest").value(false))
                .andExpect(jsonPath("$[0].cheapestSupplierId").value(depot));
    }

    @Test
    void sameDayTieGoesToTheNewestRecordAndEqualPricesAreBothCheapest() throws Exception {
        record(depot, "50", "2026-10-05");
        record(depot, "70", "2026-10-05");
        record(lupita, "70", "2026-10-04");
        api.get(owner, base + "/materials")
                .andExpect(jsonPath("$[0].latestPrices.length()").value(2))
                .andExpect(jsonPath("$[0].latestPrices[0].price").value("70.00"))
                .andExpect(jsonPath("$[0].latestPrices[0].cheapest").value(true))
                .andExpect(jsonPath("$[0].latestPrices[1].price").value("70.00"))
                .andExpect(jsonPath("$[0].latestPrices[1].cheapest").value(true));
    }

    @Test
    void detailListsTheFullHistoryNewestFirst() throws Exception {
        String oldest = record(depot, "120", "2026-09-30");
        String middle = record(lupita, "80", "2026-10-01");
        String newest = record(depot, "90", "2026-10-05");
        api.get(owner, base + "/materials/" + cement).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cement))
                .andExpect(jsonPath("$.name").value("Cemento"))
                .andExpect(jsonPath("$.latestPrices.length()").value(2))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].id").value(newest))
                .andExpect(jsonPath("$.history[1].id").value(middle))
                .andExpect(jsonPath("$.history[2].id").value(oldest))
                .andExpect(jsonPath("$.history[2].price").value("120.00"));
    }

    @Test
    void invalidPricesAndDatesAreRejectedAndNothingIsStored() throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        for (String price : new String[] {"0", "-5", "\"1.234\"", "1.234", "\"abc\""}) {
            api.post(owner, observations, body(cement, depot, price, today)).andExpect(status().isBadRequest());
        }
        String future = LocalDate.now(ZoneOffset.UTC).plusDays(2).toString();
        api.post(owner, observations, body(cement, depot, "10", future))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, observations, "{\"materialId\":\"" + cement + "\",\"supplierId\":\"" + depot + "\",\"price\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, observations, "{\"supplierId\":\"" + depot + "\",\"price\":1,\"observedOn\":\"" + today + "\"}")
                .andExpect(status().isBadRequest());
        assertEquals(0, count());
        record(depot, "10", today);
        assertEquals(1, count());
    }

    @Test
    void archivedMaterialsAndSuppliersCannotReceiveObservations() throws Exception {
        record(depot, "100", "2026-10-01");
        api.post(owner, base + "/suppliers/" + depot + "/archive", null).andExpect(status().isOk());
        api.post(owner, observations, body(cement, depot, "10", "2026-10-02"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_SUPPLIER"));
        api.get(owner, base + "/materials").andExpect(jsonPath("$[0].latestPrices.length()").value(0))
                .andExpect(jsonPath("$[0].cheapestSupplierId").value(nullValue()));
        api.get(owner, base + "/materials/" + cement).andExpect(jsonPath("$.history.length()").value(1));
        api.post(owner, base + "/materials/" + cement + "/archive", null).andExpect(status().isOk());
        api.post(owner, observations, body(cement, lupita, "10", "2026-10-02"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_MATERIAL"));
        assertEquals(1, count());
    }

    @Test
    void observationsAreImmutableButCanBeDeleted() throws Exception {
        String id = record(depot, "100", "2026-10-01");
        api.put(owner, observations + "/" + id, body(cement, depot, "1", "2026-10-01"))
                .andExpect(status().isMethodNotAllowed());
        api.patch(owner, observations + "/" + id, "{\"price\":1}").andExpect(status().isMethodNotAllowed());
        assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject(
                "select price from price_observation where id = ?", BigDecimal.class, UUID.fromString(id))));
        api.delete(owner, observations + "/" + id).andExpect(status().isNoContent());
        assertEquals(0, count());
        api.get(owner, base + "/materials/" + cement).andExpect(jsonPath("$.history.length()").value(0));
        api.delete(owner, observations + "/" + id).andExpect(status().isNotFound());
    }

    @Test
    void idsFromAnotherTenantBehaveAsNotFound() throws Exception {
        String mine = record(depot, "100", "2026-10-01");
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String theirBase = "/api/organizations/" + otherOrg;
        String theirSupplier = Api.id(api.post(stranger, theirBase + "/suppliers", "{\"name\":\"Theirs\"}"));
        String theirMaterial = Api.id(api.post(stranger, theirBase + "/materials",
                "{\"name\":\"Theirs\",\"unit\":\"BAG\",\"category\":\"STEEL\"}"));
        api.post(stranger, theirBase + "/price-observations", body(cement, theirSupplier, "1", "2026-10-01"))
                .andExpect(status().isNotFound());
        api.post(stranger, theirBase + "/price-observations", body(theirMaterial, depot, "1", "2026-10-01"))
                .andExpect(status().isNotFound());
        api.post(owner, observations, body(cement, theirSupplier, "1", "2026-10-01")).andExpect(status().isNotFound());
        api.delete(stranger, theirBase + "/price-observations/" + mine).andExpect(status().isNotFound());
        api.delete(stranger, observations + "/" + mine).andExpect(status().isNotFound());
        assertEquals(1, count());
    }
}

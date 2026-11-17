package org.andre.construx.modules.crew;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A piece of equipment linked to a Catalog Equipment rate (docs/pricing.md §6.6). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EquipmentRateFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String equipment;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        equipment = "/api/organizations/" + orgId + "/equipment";
    }

    private UUID resource(UUID organizationId, String code, String type, String hourlyCost) {
        UUID unit = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, 'Hora', 'SEED')",
                unit, organizationId, "HR-" + code);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, current_cost, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, false, false, true, false, 'MANUAL', ?, ?, ?)",
                id, organizationId, code, type, "Rate " + code, unit,
                hourlyCost == null ? null : new BigDecimal(hourlyCost), now, now);
        return id;
    }

    private String create(String extra) {
        return "{\"type\":\"Mixer\",\"identifier\":\"MX-1\"" + extra + "}";
    }

    @Test
    void linkingARatePrefillsTheDailyCostFromTheHourlyCost() throws Exception {
        UUID rate = resource(orgId, "EQ-MIX", "EQUIPMENT", "85.33");

        String id = Api.id(api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + rate + "\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dailyCostCents").value(68264))
                .andExpect(jsonPath("$.equipmentRate.id").value(rate.toString()))
                .andExpect(jsonPath("$.equipmentRate.code").value("EQ-MIX"))
                .andExpect(jsonPath("$.equipmentRate.name").value("Rate EQ-MIX"))
                .andExpect(jsonPath("$.equipmentRate.hourlyCost").value("85.33")));
        api.get(owner, equipment).andExpect(jsonPath("$[0].equipmentRate.code").value("EQ-MIX"));

        api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + rate + "\",\"dailyCostCents\":50000"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dailyCostCents").value(50000));
        api.post(owner, equipment, create("")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentRate").isEmpty());
    }

    @Test
    void theLinkIsAPointerAndUpdatingFromTheRateRefreshesTheDailyCost() throws Exception {
        UUID rate = resource(orgId, "EQ-MIX", "EQUIPMENT", "85.33");
        String id = equipment + "/" + Api.id(api.post(owner, equipment,
                create(",\"equipmentRateResourceId\":\"" + rate + "\"")));

        jdbc.update("update resource set current_cost = ? where id = ?", new BigDecimal("100.00"), rate);
        api.get(owner, equipment).andExpect(jsonPath("$[0].dailyCostCents").value(68264))
                .andExpect(jsonPath("$[0].equipmentRate.hourlyCost").value("100.00"));

        api.patch(owner, id, "{\"identifier\":\"MX-2\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCostCents").value(68264))
                .andExpect(jsonPath("$.equipmentRate.code").value("EQ-MIX"));
        api.patch(owner, id, "{\"equipmentRateResourceId\":\"" + rate + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCostCents").value(80000));
        api.patch(owner, id, "{\"equipmentRateResourceId\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentRate").isEmpty())
                .andExpect(jsonPath("$.dailyCostCents").value(80000));
    }

    @Test
    void onlyEquipmentRatesOfTheOrganizationCanBeLinked() throws Exception {
        UUID material = resource(orgId, "MAT-1", "MATERIAL", "10.00");
        UUID foreign = resource(api.createOrganization(api.signUp(), "Other " + UUID.randomUUID()),
                "EQ-X", "EQUIPMENT", "10.00");
        UUID costless = resource(orgId, "EQ-NEW", "EQUIPMENT", null);

        api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + material + "\""))
                .andExpect(status().isBadRequest());
        api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + foreign + "\""))
                .andExpect(status().isNotFound());
        api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + UUID.randomUUID() + "\""))
                .andExpect(status().isNotFound());
        api.post(owner, equipment, create(",\"equipmentRateResourceId\":\"" + costless + "\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dailyCostCents").isEmpty())
                .andExpect(jsonPath("$.equipmentRate.hourlyCost").isEmpty());

        String id = equipment + "/" + Api.id(api.post(owner, equipment, create("")));
        api.patch(owner, id, "{\"equipmentRateResourceId\":\"" + material + "\"}").andExpect(status().isBadRequest());
        api.patch(owner, id, "{\"equipmentRateResourceId\":\"" + foreign + "\"}").andExpect(status().isNotFound());
        api.patch(owner, id, "{\"equipmentRateResourceId\":\"not-a-uuid\"}").andExpect(status().isBadRequest());
    }
}

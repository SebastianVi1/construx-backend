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
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Composition of the Catalog: Unit price analyses with their derived cost, Labor categories with their wage
 * and Equipment rates with their machinery data (docs/pricing.md §1.7-1.9, §2.4, §3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceCompositionFlowTest {
    private static final Instant IMPORTED_AT = Instant.parse("2026-11-01T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private String base;
    private CatalogSeed seed;
    private UUID jor;
    private UUID bag;
    private UUID m2;
    private UUID peon;
    private UUID oficial;
    private UUID gang;
    private UUID cement;
    private UUID tools;
    private UUID wall;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        UUID orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        seed = new CatalogSeed(jdbc, orgId);
        seed.catalog();
        jor = seed.unit("JOR", "Jornada");
        bag = seed.unit("SACO", "Saco");
        m2 = seed.unit("M2", "Metro cuadrado");
    }

    /**
     * An imported catalog: a wall of block (Work item) uses cement, a Gang of one oficial and one peon at an
     * output of 8 m2 per day, and tools at 3 % of its labor.
     */
    private void importedWall() {
        peon = labor("MO-PEON", "Peon", "333.33", "1.5", "500.00");
        oficial = labor("MO-OFI", "Oficial albanil", "500.00", "1.6", "800.00");
        gang = seed.composite("CUAD-01", "LABOR", "Cuadrilla oficial + peon", jor);
        seed.line(gang, 1, oficial, "*", "1");
        seed.line(gang, 2, peon, "*", "1");
        cement = seed.resource("CEM-01", "MATERIAL", "Cemento gris", bag);
        seed.cost(cement, "245.50", IMPORTED_AT, "NEODATA_CONSTRUBASE", null, null, null, null);
        tools = seed.percentage("%MO1", "Herramienta menor", jor);
        seed.cost(tools, "0.00", IMPORTED_AT, "NEODATA_CONSTRUBASE", null, null, null, null);
        wall = seed.composite("BLOCK-01", "WORK_ITEM", "Muro de block", m2);
        seed.line(wall, 1, cement, "*", "0.03");
        seed.line(wall, 2, gang, "/", "8");
        seed.line(wall, 3, tools, "*", "0.03");
        seed.set(wall, "imported_cost", new BigDecimal("170.00"));
    }

    private UUID labor(String code, String name, String nominalWage, String fsr, String realWage) {
        UUID id = seed.resource(code, "LABOR", name, jor);
        seed.laborDetail(id, nominalWage, fsr, realWage);
        seed.cost(id, realWage, IMPORTED_AT, "NEODATA_CONSTRUBASE", null, null, null, null);
        return id;
    }

    @Test
    void aWorkItemShowsItsAnalysisWithLaborSubtotalAndTheFileMismatch() throws Exception {
        importedWall();

        api.get(owner, base + "/resources/" + wall).andExpect(status().isOk())
                .andExpect(jsonPath("$.isComposite").value(true))
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.laborDetail").isEmpty())
                .andExpect(jsonPath("$.analysis.derivedCost").value("174.75"))
                .andExpect(jsonPath("$.analysis.importedCost").value("170.00"))
                .andExpect(jsonPath("$.analysis.mismatch").value(true))
                .andExpect(jsonPath("$.analysis.incomplete").value(false))
                .andExpect(jsonPath("$.analysis.laborSubtotal").value("162.50"))
                .andExpect(jsonPath("$.analysis.lines.length()").value(3))
                .andExpect(jsonPath("$.analysis.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.analysis.lines[0].component.code").value("CEM-01"))
                .andExpect(jsonPath("$.analysis.lines[0].component.type").value("MATERIAL"))
                .andExpect(jsonPath("$.analysis.lines[0].component.unitCode").value("SACO"))
                .andExpect(jsonPath("$.analysis.lines[0].component.currentCost").value("245.50"))
                .andExpect(jsonPath("$.analysis.lines[0].operator").value("*"))
                .andExpect(jsonPath("$.analysis.lines[0].quantity").value("0.03"))
                .andExpect(jsonPath("$.analysis.lines[0].amount").value("7.37"))
                .andExpect(jsonPath("$.analysis.lines[1].component.id").value(gang.toString()))
                .andExpect(jsonPath("$.analysis.lines[1].operator").value("/"))
                .andExpect(jsonPath("$.analysis.lines[1].amount").value("162.50"))
                .andExpect(jsonPath("$.analysis.lines[2].component.isPercentage").value(true))
                .andExpect(jsonPath("$.analysis.lines[2].amount").value("4.88"));

        api.get(owner, base + "/resources/" + gang).andExpect(jsonPath("$.analysis.derivedCost").value("1300.00"))
                .andExpect(jsonPath("$.analysis.laborSubtotal").value("1300.00"))
                .andExpect(jsonPath("$.analysis.mismatch").value(false))
                .andExpect(jsonPath("$.analysis.importedCost").isEmpty());
    }

    @Test
    void recordingACostRecomputesEveryParentWithoutHistory() throws Exception {
        importedWall();

        api.post(owner, base + "/resources/" + cement + "/costs", "{\"amount\":\"250.00\"}")
                .andExpect(status().isCreated());

        api.get(owner, base + "/resources/" + wall).andExpect(jsonPath("$.currentCost").value("174.88"))
                .andExpect(jsonPath("$.costUpdatedAt", notNullValue()))
                .andExpect(jsonPath("$.analysis.lines[0].amount").value("7.50"));
        api.get(owner, base + "/resources/" + gang).andExpect(jsonPath("$.currentCost").value("1300.00"));
        api.get(owner, base + "/resources?type=WORK_ITEM")
                .andExpect(jsonPath("$.content[0].currentCost").value("174.88"));
        api.get(owner, base + "/resources/" + wall + "/costs").andExpect(jsonPath("$.totalElements").value(0));
        BigDecimal materialized = jdbc.queryForObject(
                "select amount from unit_price_analysis_line where parent_resource_id = ? and line_no = 3",
                BigDecimal.class, wall);
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("4.88"), materialized);
    }

    @Test
    void aComponentWithoutCostMakesTheAnalysisIncomplete() throws Exception {
        importedWall();
        UUID water = seed.resource("AGUA", "MATERIAL", "Agua", m2);
        UUID mortar = seed.composite("MORT-01", "MATERIAL", "Mortero", m2);
        seed.line(mortar, 1, water, "*", "0.2");
        seed.line(mortar, 2, cement, "*", "0.25");
        seed.line(wall, 4, mortar, "*", "0.01");

        api.get(owner, base + "/resources/" + mortar).andExpect(jsonPath("$.analysis.incomplete").value(true))
                .andExpect(jsonPath("$.analysis.derivedCost").value("61.38"))
                .andExpect(jsonPath("$.analysis.lines[0].component.currentCost").isEmpty());
        api.get(owner, base + "/resources/" + wall).andExpect(jsonPath("$.analysis.incomplete").value(true))
                .andExpect(jsonPath("$.analysis.lines[3].component.currentCost").isEmpty())
                .andExpect(jsonPath("$.analysis.lines[3].amount").value("0.61"));
    }

    @Test
    void usedInCountsTheParentsAndShowsASample() throws Exception {
        importedWall();
        UUID otherWall = seed.composite("BLOCK-02", "WORK_ITEM", "Muro de block 2", m2);
        seed.line(otherWall, 1, cement, "*", "0.05");

        api.get(owner, base + "/resources/" + cement).andExpect(jsonPath("$.usedIn.count").value(2))
                .andExpect(jsonPath("$.usedIn.sample.length()").value(2))
                .andExpect(jsonPath("$.usedIn.sample[0].code").value("BLOCK-01"))
                .andExpect(jsonPath("$.usedIn.sample[0].type").value("WORK_ITEM"))
                .andExpect(jsonPath("$.usedIn.sample[1].name").value("Muro de block 2"));
        api.get(owner, base + "/resources/" + peon).andExpect(jsonPath("$.usedIn.count").value(1))
                .andExpect(jsonPath("$.usedIn.sample[0].id").value(gang.toString()));
        api.get(owner, base + "/resources/" + wall).andExpect(jsonPath("$.usedIn.count").value(0));
    }

    @Test
    void anImportedLaborCategoryShowsItsWage() throws Exception {
        importedWall();

        api.get(owner, base + "/resources/" + peon).andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").isEmpty())
                .andExpect(jsonPath("$.laborDetail.nominalWage").value("333.33"))
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.5"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("IMPORTED"))
                .andExpect(jsonPath("$.laborDetail.perDiem").value("0.00"))
                .andExpect(jsonPath("$.laborDetail.imported.fsr").value("1.5"))
                .andExpect(jsonPath("$.laborDetail.imported.realWage").value("500.00"))
                .andExpect(jsonPath("$.laborDetail.fsrMismatch").value(false))
                .andExpect(jsonPath("$.laborDetail.realWage").value("500.00"))
                .andExpect(jsonPath("$.currentCost").value("500.00"));
        api.get(owner, base + "/resources?type=LABOR&composite=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("CUAD-01"));
    }

    @Test
    void changingAWageWritesHistoryAndRecomputesTheParents() throws Exception {
        importedWall();

        api.patch(owner, base + "/resources/" + peon, "{\"laborDetail\":{\"nominalWage\":\"400\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.laborDetail.nominalWage").value("400.00"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("IMPORTED"))
                .andExpect(jsonPath("$.laborDetail.realWage").value("600.00"))
                .andExpect(jsonPath("$.currentCost").value("600.00"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].amount").value("600.00"))
                .andExpect(jsonPath("$.history[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId", notNullValue()));

        api.get(owner, base + "/resources/" + gang).andExpect(jsonPath("$.currentCost").value("1400.00"));
        api.get(owner, base + "/resources/" + wall).andExpect(jsonPath("$.currentCost").value("187.62"))
                .andExpect(jsonPath("$.analysis.laborSubtotal").value("175.00"));

        api.patch(owner, base + "/resources/" + peon, "{\"laborDetail\":{\"fsr\":\"1.5\"},\"name\":\"Peon A\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Peon A"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("MANUAL"))
                .andExpect(jsonPath("$.history.length()").value(2));
    }

    @Test
    void createsALaborCategoryByHandFromItsWage() throws Exception {
        String body = "{\"type\":\"LABOR\",\"code\":\"mo-cab\",\"name\":\"Cabo\",\"unitId\":\"" + jor + "\","
                + "\"laborDetail\":{\"nominalWage\":\"350.00\",\"fsr\":\"1.7\",\"perDiem\":\"10.50\"}}";

        api.post(owner, base + "/resources", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LABOR"))
                .andExpect(jsonPath("$.code").value("MO-CAB"))
                .andExpect(jsonPath("$.isComposite").value(false))
                .andExpect(jsonPath("$.currentCost").value("605.50"))
                .andExpect(jsonPath("$.laborDetail.nominalWage").value("350.00"))
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.7"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("MANUAL"))
                .andExpect(jsonPath("$.laborDetail.perDiem").value("10.50"))
                .andExpect(jsonPath("$.laborDetail.mealsLodging").value("0.00"))
                .andExpect(jsonPath("$.laborDetail.imported.realWage").isEmpty())
                .andExpect(jsonPath("$.laborDetail.realWage").value("605.50"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].amount").value("605.50"));
    }

    @Test
    void validatesAHandMadeLaborCategory() throws Exception {
        String start = "{\"type\":\"LABOR\",\"code\":\"MO-X\",\"name\":\"X\",\"unitId\":\"" + jor + "\"";
        for (String bad : new String[] {
                start + ",\"cost\":\"100\",\"laborDetail\":{\"nominalWage\":\"350\",\"fsr\":\"1.7\"}}",
                start + ",\"laborDetail\":{\"fsr\":\"1.7\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"350\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"350\",\"fsr\":\"3.01\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"350\",\"fsr\":\"0.99\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"-1\",\"fsr\":\"1.7\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"350.001\",\"fsr\":\"1.7\"}}",
                start + ",\"laborDetail\":{\"nominalWage\":\"350\",\"fsr\":\"1.7\",\"perDiem\":\"-5\"}}"}) {
            api.post(owner, base + "/resources", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        api.post(owner, base + "/resources", start + "}").andExpect(status().isBadRequest());
        api.get(owner, base + "/resources").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void laborDetailOnlyFitsASimpleLaborCategory() throws Exception {
        importedWall();
        String patch = "{\"laborDetail\":{\"nominalWage\":\"400\"}}";

        api.patch(owner, base + "/resources/" + cement, patch).andExpect(status().isBadRequest());
        api.patch(owner, base + "/resources/" + gang, patch).andExpect(status().isBadRequest());
        api.patch(owner, base + "/resources/" + peon, "{\"laborDetail\":{\"fsr\":\"4\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, base + "/resources/" + peon).andExpect(jsonPath("$.laborDetail.fsr").value("1.5"));
    }

    @Test
    void costsOfCompositesAndLaborCategoriesAreNotTypedIn() throws Exception {
        importedWall();

        api.post(owner, base + "/resources/" + wall + "/costs", "{\"amount\":\"1.00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DERIVED_COST"));
        api.post(owner, base + "/resources/" + peon + "/costs", "{\"amount\":\"1.00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LABOR_COST_FROM_WAGE"));
    }

    @Test
    void anEquipmentRateShowsItsMachineryData() throws Exception {
        importedWall();
        UUID diesel = seed.resource("DIESEL", "MATERIAL", "Diesel", bag);
        UUID backhoe = seed.resource("EQ-RETRO", "EQUIPMENT", "Retroexcavadora", jor);
        seed.cost(backhoe, "650.00", IMPORTED_AT, "NEODATA_CONSTRUBASE", null, null, null, null);
        seed.equipmentDetail(backhoe);
        seed.equipmentLine(true, backhoe, 1, oficial, "*", "1");
        seed.equipmentLine(false, backhoe, 1, diesel, "*", "12.5");
        seed.standby("CARGOS FIJOS", "0.3", "0.1");
        seed.standby("ACEITE", "0", "0.2");

        api.get(owner, base + "/resources/" + backhoe).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCost").value("650.00"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.equipmentDetail.powerUnit").value("HP"))
                .andExpect(jsonPath("$.equipmentDetail.power").value("95.5"))
                .andExpect(jsonPath("$.equipmentDetail.economicLifeHours").value("12000"))
                .andExpect(jsonPath("$.equipmentDetail.salvagePct").value("0.1"))
                .andExpect(jsonPath("$.equipmentDetail.brand").value("CAT"))
                .andExpect(jsonPath("$.equipmentDetail.commercialValue").value("1500000.00"))
                .andExpect(jsonPath("$.equipmentDetail.fuelCode").isEmpty())
                .andExpect(jsonPath("$.equipmentDetail.operationLines.length()").value(1))
                .andExpect(jsonPath("$.equipmentDetail.operationLines[0].component.code").value("MO-OFI"))
                .andExpect(jsonPath("$.equipmentDetail.operationLines[0].component.unit.code").value("JOR"))
                .andExpect(jsonPath("$.equipmentDetail.operationLines[0].operator").value("*"))
                .andExpect(jsonPath("$.equipmentDetail.consumptionLines[0].component.name").value("Diesel"))
                .andExpect(jsonPath("$.equipmentDetail.consumptionLines[0].quantity").value("12.5"));

        api.get(owner, base + "/equipment-standby-percentages").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].charge").value("ACEITE"))
                .andExpect(jsonPath("$[1].standbyPct").value("0.3"))
                .andExpect(jsonPath("$[1].reservePct").value("0.1"));

        api.get(owner, base + "/resources/" + cement).andExpect(jsonPath("$.equipmentDetail").isEmpty());
    }
}

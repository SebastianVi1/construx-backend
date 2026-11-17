package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASAR parameters (docs/pricing.md §2.6, §5): reading, replacing, and the recomputation of every Labor
 * category's FSR and real wage that a replace triggers. Parameters as in {@code FasarCalculatorTest}: for a
 * nominal wage of 400.00 they give FSR 1.751037, SBC 418.08 and a real wage of round(400 × 1.751037, 2) = 700.41.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FasarFlowTest {
    private static final Instant IMPORTED_AT = Instant.parse("2026-11-01T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String base;
    private CatalogSeed seed;
    private UUID jor;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        seed = new CatalogSeed(jdbc, orgId);
        seed.catalog();
        jor = seed.unit("JOR", "Jornada");
    }

    private static String body(String overrides) {
        String contributions = """
                [{"concept":"RISK","employerRate":"0.0758875","workerRate":"0"},
                 {"concept":"FIXED_FEE","employerRate":"0.204","workerRate":"0"},
                 {"concept":"EXCESS_3_UMA","employerRate":"0.011","workerRate":"0.004"},
                 {"concept":"CASH_BENEFITS","employerRate":"0.007","workerRate":"0.0025"},
                 {"concept":"PENSIONERS_MEDICAL","employerRate":"0.0105","workerRate":"0.00375"},
                 {"concept":"DISABILITY_LIFE","employerRate":"0.0175","workerRate":"0.00625"},
                 {"concept":"RETIREMENT","employerRate":"0.02","workerRate":"0"},
                 {"concept":"UNEMPLOYMENT_OLD_AGE","employerRate":"0.0315","workerRate":"0.01125"},
                 {"concept":"DAYCARE","employerRate":"0.01","workerRate":"0"},
                 {"concept":"INFONAVIT","employerRate":"0.05","workerRate":"0"},
                 {"concept":"PAYROLL_TAX","employerRate":"0.03","workerRate":"0"},
                 {"concept":"OTHER_CHARGES","employerRate":"0.01","workerRate":"0"}]""";
        String days = """
                {"calendar":"365","sundays":"52","workedSundays":"0","vacation":"6","mondays":"0","holidays":"7.5",
                 "weather":"6","custom":"2","leaveSick":"3","union":"0","bonus":"15"}""";
        String json = """
                {"year":2026,"uma":"86.88","minimumWage":"248.93","sumEmployerAndWorker":true,
                 "days":%s,
                 "premiums":{"vacationPct":"0.25","sundayPct":"0.25","otherChargesPct":"0"},
                 "caps":{"excessThresholdUma":"3","sbcCapUma":"25","factorRounding":6},
                 "contributions":%s}""".formatted(days, contributions);
        return overrides == null ? json : overrides.replace("$DAYS", days).replace("$CONTRIBUTIONS", contributions);
    }

    /** An imported Labor category whose file FSR and real wage are kept for verification. */
    private UUID imported(String code, String nominalWage, String fsr, String realWage) {
        UUID id = seed.resource(code, "LABOR", code, jor);
        seed.laborDetail(id, nominalWage, fsr, realWage);
        seed.cost(id, realWage, IMPORTED_AT, "NEODATA_CONSTRUBASE", null, null, null, null);
        return id;
    }

    @Test
    void withoutParametersTheStatusIsNone() throws Exception {
        api.get(owner, base + "/fasar").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(jsonPath("$.version").isEmpty())
                .andExpect(jsonPath("$.uma").isEmpty());
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.fasar.status").value("NONE"));
    }

    @Test
    void replacingStoresACompleteManualSetAndBumpsTheVersion() throws Exception {
        api.put(owner, base + "/fasar", body(null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.uma").value("86.8800"))
                .andExpect(jsonPath("$.minimumWage").value("248.93"))
                .andExpect(jsonPath("$.days.holidays").value("7.5"))
                .andExpect(jsonPath("$.days.calendar").value("365"))
                .andExpect(jsonPath("$.premiums.vacationPct").value("0.25"))
                .andExpect(jsonPath("$.caps.factorRounding").value(6))
                .andExpect(jsonPath("$.contributions.length()").value(12))
                .andExpect(jsonPath("$.contributions[0].concept").value("RISK"))
                .andExpect(jsonPath("$.contributions[0].employerRate").value("0.0758875"))
                .andExpect(jsonPath("$.derived.tp").value("381.5"))
                .andExpect(jsonPath("$.derived.tl").value("288.5"))
                .andExpect(jsonPath("$.derived.tpOverTl").value("1.32235702"))
                .andExpect(jsonPath("$.derived.fsbc").value("1.04520548"))
                .andExpect(jsonPath("$.updatedBy.membershipId").isNotEmpty());

        api.put(owner, base + "/fasar", body(null)).andExpect(jsonPath("$.version").value(2));
        api.get(owner, base + "/fasar").andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.days.leaveSick").value("3"));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.fasar.status").value("COMPLETE"))
                .andExpect(jsonPath("$.fasar.year").value(2026))
                .andExpect(jsonPath("$.fasar.uma").value("86.8800"))
                .andExpect(jsonPath("$.fasar.minimumWage").value("248.93"));
    }

    @Test
    void replacingRecomputesEveryLaborCategoryWithHistoryAndDerivedCosts() throws Exception {
        UUID peon = imported("MO-PEON", "400.00", "1.5", "600.00");
        UUID gang = seed.composite("CUAD-01", "LABOR", "Cuadrilla", jor);
        seed.line(gang, 1, peon, "*", "1");
        UUID wall = seed.composite("BLOCK-01", "WORK_ITEM", "Muro", seed.unit("M2", "m2"));
        seed.line(wall, 1, gang, "/", "8");

        api.put(owner, base + "/fasar", body(null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recomputation.laborCategories").value(1))
                .andExpect(jsonPath("$.recomputation.realWagesChanged").value(1))
                .andExpect(jsonPath("$.recomputation.workItemsRecomputed").value(1))
                .andExpect(jsonPath("$.laborCategories.total").value(1))
                .andExpect(jsonPath("$.laborCategories.computed").value(1))
                .andExpect(jsonPath("$.laborCategories.mismatches").value(1));

        api.get(owner, base + "/resources/" + peon)
                .andExpect(jsonPath("$.currentCost").value("700.41"))
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.751037"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("COMPUTED"))
                .andExpect(jsonPath("$.laborDetail.fsbc").value("1.04520548"))
                .andExpect(jsonPath("$.laborDetail.sbc").value("418.08"))
                .andExpect(jsonPath("$.laborDetail.realWage").value("700.41"))
                .andExpect(jsonPath("$.laborDetail.fsrMismatch").value(true))
                .andExpect(jsonPath("$.laborDetail.imported.fsr").value("1.5"))
                .andExpect(jsonPath("$.history[0].amount").value("700.41"))
                .andExpect(jsonPath("$.history[0].source").value("FASAR"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.history[1].amount").value("600.00"));
        // 700.41 / 8 = 87.55 (it was 600.00 / 8 = 75.00).
        api.get(owner, base + "/resources/" + wall).andExpect(jsonPath("$.currentCost").value("87.55"));

        api.put(owner, base + "/fasar", body(null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recomputation.laborCategories").value(1))
                .andExpect(jsonPath("$.recomputation.realWagesChanged").value(0))
                .andExpect(jsonPath("$.recomputation.workItemsRecomputed").value(0));
        api.get(owner, base + "/resources/" + peon + "/costs").andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void aCategoryThatMatchesTheFileWithinToleranceIsNotFlagged() throws Exception {
        UUID oficial = imported("MO-OFI", "400.00", "1.7512", "700.45");

        api.put(owner, base + "/fasar", body(null)).andExpect(jsonPath("$.laborCategories.mismatches").value(0));

        api.get(owner, base + "/resources/" + oficial)
                .andExpect(jsonPath("$.laborDetail.fsrMismatch").value(false))
                .andExpect(jsonPath("$.currentCost").value("700.41"));
    }

    @Test
    void anInvalidSetIsRefusedAndNothingIsStored() throws Exception {
        String missingConcept = body(null).replace(
                "{\"concept\":\"DAYCARE\",\"employerRate\":\"0.01\",\"workerRate\":\"0\"},", "");
        api.put(owner, base + "/fasar", missingConcept).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        String rateAboveOne = body(null).replace("\"employerRate\":\"0.204\"", "\"employerRate\":\"1.5\"");
        api.put(owner, base + "/fasar", rateAboveOne).andExpect(status().isBadRequest());

        String noWorkingDays = body(null).replace("\"sundays\":\"52\"", "\"sundays\":\"345\"");
        api.put(owner, base + "/fasar", noWorkingDays).andExpect(status().isBadRequest());

        String noUma = body(null).replace("\"uma\":\"86.88\",", "");
        api.put(owner, base + "/fasar", noUma).andExpect(status().isBadRequest());

        api.get(owner, base + "/fasar").andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    void withACompleteSetANewLaborCategoryGetsAComputedFsr() throws Exception {
        api.put(owner, base + "/fasar", body(null)).andExpect(status().isOk());

        String created = api.id(api.post(owner, base + "/resources", """
                {"type":"LABOR","code":"MO-AYU","name":"Ayudante","unitId":"%s",
                 "laborDetail":{"nominalWage":"400.00","fsr":"2.5"}}""".formatted(jor))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("COMPUTED"))
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.751037"))
                .andExpect(jsonPath("$.currentCost").value("700.41")));

        // Editing the wage keeps the FSR computed: 200.00 gives FSR 1.799625 and 359.93.
        api.patch(owner, base + "/resources/" + created, "{\"laborDetail\":{\"nominalWage\":\"200.00\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.799625"))
                .andExpect(jsonPath("$.laborDetail.sbc").value("209.04"))
                .andExpect(jsonPath("$.currentCost").value("359.93"));
    }

    @Test
    void membersReadAndOnlyManagersReplace() throws Exception {
        Session member = api.signUp();
        new Members(txm, organizations, memberships, projects, accesses).add(orgId, member.user(), "MEMBER");

        api.get(member, base + "/fasar").andExpect(status().isOk());
        api.put(member, base + "/fasar", body(null)).andExpect(status().isForbidden());

        Session stranger = api.signUp();
        api.get(stranger, base + "/fasar").andExpect(status().isNotFound());
        api.put(stranger, base + "/fasar", body(null)).andExpect(status().isNotFound());
    }
}

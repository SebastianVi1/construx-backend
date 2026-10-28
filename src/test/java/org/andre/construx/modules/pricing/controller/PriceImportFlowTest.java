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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSV import contract: odd/tasks/material-prices-csv-contract.md. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceImportFlowTest {
    private static final String HEADER = "material,unit,category,brand,supplier,branch,price,observed_on,sku\n";
    private static final String THREE_ROWS = HEADER
            + "cemento  GRIS 50 kg,saco,cemento y agregados,,Home Depot,Tlalpan,245.5,2026-10-20,\n"
            + "Varilla 3/8,PIECE,STEEL,Deacero,Home Depot,Tlalpan,98,2026-10-20,V-38\n"
            + "varilla 3/8,pieza,acero,DEACERO,Lupita,,99.9,2026-10-21,\n";

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
    private String cement;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        cement = Api.id(api.post(owner, base + "/materials",
                "{\"name\":\"Cemento gris 50 kg\",\"unit\":\"BAG\",\"category\":\"CEMENT_AGGREGATES\"}")
                .andExpect(status().isCreated()));
    }

    private ResultActions upload(Session s, String csv, String dryRun) throws Exception {
        return upload(s, csv.getBytes(StandardCharsets.UTF_8), dryRun);
    }

    private ResultActions upload(Session s, byte[] content, String dryRun) throws Exception {
        return mvc.perform(multipart(base + "/price-observations/import")
                .file(new MockMultipartFile("file", "prices.csv", "text/csv", content))
                .param("dryRun", dryRun)
                .cookie(s.access(), s.csrf().cookie()).header(s.csrf().header(), s.csrf().token()));
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where organization_id = ?", Long.class, orgId);
    }

    private void assertNothingWritten() {
        assertEquals(1L, count("material"));
        assertEquals(0L, count("supplier"));
        assertEquals(0L, count("price_observation"));
    }

    @Test
    void dryRunPreviewsEveryRowWithoutWritingAndReusesNewEntriesWithinTheFile() throws Exception {
        upload(owner, THREE_ROWS, "true").andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].line").value(2))
                .andExpect(jsonPath("$.rows[0].material").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.rows[0].supplier").value("Home Depot"))
                .andExpect(jsonPath("$.rows[0].branch").value("Tlalpan"))
                .andExpect(jsonPath("$.rows[0].price").value("245.50"))
                .andExpect(jsonPath("$.rows[0].observedOn").value("2026-10-20"))
                .andExpect(jsonPath("$.rows[0].newMaterial").value(false))
                .andExpect(jsonPath("$.rows[0].newSupplier").value(true))
                .andExpect(jsonPath("$.rows[0].errors.length()").value(0))
                .andExpect(jsonPath("$.rows[1].line").value(3))
                .andExpect(jsonPath("$.rows[1].newMaterial").value(true))
                .andExpect(jsonPath("$.rows[1].newSupplier").value(true))
                .andExpect(jsonPath("$.rows[2].line").value(4))
                .andExpect(jsonPath("$.rows[2].branch").doesNotExist())
                .andExpect(jsonPath("$.rows[2].price").value("99.90"))
                .andExpect(jsonPath("$.rows[2].newMaterial").value(true))
                .andExpect(jsonPath("$.rows[2].newSupplier").value(true))
                .andExpect(jsonPath("$.summary.rows").value(3))
                .andExpect(jsonPath("$.summary.newMaterials").value(1))
                .andExpect(jsonPath("$.summary.newSuppliers").value(2))
                .andExpect(jsonPath("$.summary.observations").value(3));
        assertNothingWritten();
    }

    @Test
    void importWritesMissingEntriesOnceAndRecordsCsvObservations() throws Exception {
        upload(owner, THREE_ROWS, "false").andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.imported").value(true))
                .andExpect(jsonPath("$.summary.observations").value(3));
        assertEquals(2L, count("material"));
        assertEquals(2L, count("supplier"));
        assertEquals(3L, count("price_observation"));
        assertEquals(List.of("CSV"), jdbc.queryForList(
                "select distinct source from price_observation where organization_id = ?", String.class, orgId));
        Map<String, Object> rebar = jdbc.queryForMap(
                "select * from material where organization_id = ? and name = 'Varilla 3/8'", orgId);
        assertEquals("PIECE", rebar.get("unit"));
        assertEquals("STEEL", rebar.get("category"));
        assertEquals("Deacero", rebar.get("brand"));
        assertEquals(0, new BigDecimal("245.50").compareTo(jdbc.queryForObject(
                "select price from price_observation where material_id = ?", BigDecimal.class, UUID.fromString(cement))));
        api.get(owner, base + "/materials/" + cement).andExpect(jsonPath("$.history[0].source").value("CSV"))
                .andExpect(jsonPath("$.history[0].supplierBranch").value("Tlalpan"));
        api.post(owner, base + "/materials", "{\"name\":\"VARILLA  3/8\",\"unit\":\"PIECE\",\"category\":\"STEEL\","
                + "\"brand\":\"deacero\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL"));
        api.post(owner, base + "/suppliers", "{\"name\":\"lupita\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        upload(owner, HEADER + "Varilla 3/8,pieza,acero,Deacero,LUPITA,,100,2026-10-22,\n", "false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].newMaterial").value(false))
                .andExpect(jsonPath("$.rows[0].newSupplier").value(false));
        assertEquals(2L, count("material"));
        assertEquals(2L, count("supplier"));
    }

    private static final String TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    private static final String BAD_ROWS = HEADER
            + "Arena,m3,cemento y agregados,,Depot,,10,2026-10-20,\n"
            + ",kilo,,,Depot,,0,2026-13-01,\n"
            + "Arena fina,m³,cemento y agregados,,Depot,,1.234," + TOMORROW + ",\n"
            + "\"Cal, hidratada\",kg,acabados\n"
            + "Grava,m3,x,,,,$12,20/10/2026,\n";

    private ResultActions expectBadRowReport(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.imported").value(false))
                .andExpect(jsonPath("$.rows.length()").value(5))
                .andExpect(jsonPath("$.rows[0].line").value(2))
                .andExpect(jsonPath("$.rows[0].errors.length()").value(0))
                .andExpect(jsonPath("$.rows[1].line").value(3))
                .andExpect(jsonPath("$.rows[1].errors.length()").value(5))
                .andExpect(jsonPath("$.rows[1].errors[0].field").value("material"))
                .andExpect(jsonPath("$.rows[1].errors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.rows[1].errors[1].field").value("unit"))
                .andExpect(jsonPath("$.rows[1].errors[1].code").value("INVALID"))
                .andExpect(jsonPath("$.rows[1].errors[2].field").value("category"))
                .andExpect(jsonPath("$.rows[1].errors[2].code").value("REQUIRED"))
                .andExpect(jsonPath("$.rows[1].errors[3].field").value("price"))
                .andExpect(jsonPath("$.rows[1].errors[3].code").value("INVALID"))
                .andExpect(jsonPath("$.rows[1].errors[4].field").value("observed_on"))
                .andExpect(jsonPath("$.rows[1].errors[4].code").value("INVALID"))
                .andExpect(jsonPath("$.rows[2].line").value(4))
                .andExpect(jsonPath("$.rows[2].errors.length()").value(2))
                .andExpect(jsonPath("$.rows[2].errors[0].field").value("price"))
                .andExpect(jsonPath("$.rows[2].errors[0].code").value("TOO_MANY_DECIMALS"))
                .andExpect(jsonPath("$.rows[2].errors[1].field").value("observed_on"))
                .andExpect(jsonPath("$.rows[2].errors[1].code").value("FUTURE_DATE"))
                .andExpect(jsonPath("$.rows[3].line").value(5))
                .andExpect(jsonPath("$.rows[3].errors.length()").value(1))
                .andExpect(jsonPath("$.rows[3].errors[0].field").value("row"))
                .andExpect(jsonPath("$.rows[3].errors[0].code").value("COLUMNS"))
                .andExpect(jsonPath("$.rows[4].line").value(6))
                .andExpect(jsonPath("$.rows[4].errors.length()").value(4))
                .andExpect(jsonPath("$.rows[4].errors[0].field").value("category"))
                .andExpect(jsonPath("$.rows[4].errors[1].field").value("supplier"))
                .andExpect(jsonPath("$.rows[4].errors[1].code").value("REQUIRED"))
                .andExpect(jsonPath("$.rows[4].errors[2].field").value("price"))
                .andExpect(jsonPath("$.rows[4].errors[3].field").value("observed_on"))
                .andExpect(jsonPath("$.summary.rows").value(5))
                .andExpect(jsonPath("$.summary.newMaterials").value(1))
                .andExpect(jsonPath("$.summary.newSuppliers").value(1))
                .andExpect(jsonPath("$.summary.observations").value(1));
    }

    @Test
    void dryRunReportsEveryRowErrorWithItsLineFieldAndCode() throws Exception {
        expectBadRowReport(upload(owner, BAD_ROWS, "true").andExpect(status().isOk()));
        assertNothingWritten();
    }

    @Test
    void importWithAnyInvalidRowAnswers422WithTheReportAndWritesNothing() throws Exception {
        expectBadRowReport(upload(owner, BAD_ROWS, "false").andExpect(status().isUnprocessableContent()));
        assertNothingWritten();
    }

    @Test
    void unitAndCategoryAcceptEnumCodesAndSpanishLabelsIgnoringCaseAndAccents() throws Exception {
        String[][] cases = {
                {"pieza", "PIECE"}, {"Saco", "BAG"}, {"m²", "SQUARE_METER"}, {"M2", "SQUARE_METER"},
                {"m³", "CUBIC_METER"}, {"m3", "CUBIC_METER"}, {"KG", "KILOGRAM"}, {"Litro", "LITER"},
                {"metro", "METER"}, {"square_meter", "SQUARE_METER"}, {"LITER", "LITER"}};
        String[][] categories = {
                {"Cemento y Agregados", "CEMENT_AGGREGATES"}, {"ACERO", "STEEL"}, {"madera", "WOOD"},
                {"Eléctrico", "ELECTRICAL"}, {"electrico", "ELECTRICAL"}, {"PLOMERÍA", "PLUMBING"},
                {"plomeria", "PLUMBING"}, {"Acabados", "FINISHES"}, {"WOOD", "WOOD"}, {"finishes", "FINISHES"},
                {"CEMENT_AGGREGATES", "CEMENT_AGGREGATES"}};
        StringBuilder csv = new StringBuilder(HEADER.toUpperCase());
        for (int i = 0; i < cases.length; i++) {
            csv.append("Item ").append(i).append(',').append(cases[i][0]).append(',').append(categories[i][0])
                    .append(",,Depot,,1,2026-10-20,\n");
        }
        upload(owner, csv.toString(), "false").andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(true));
        for (int i = 0; i < cases.length; i++) {
            Map<String, Object> row = jdbc.queryForMap(
                    "select unit, category from material where organization_id = ? and name = ?", orgId, "Item " + i);
            assertEquals(cases[i][1], row.get("unit"), cases[i][0]);
            assertEquals(categories[i][1], row.get("category"), categories[i][0]);
        }
    }

    @Test
    void quotedFieldsMayContainCommasQuotesAndTheFileMayStartWithABom() throws Exception {
        String csv = "﻿" + HEADER.replace("\n", "\r\n")
                + "\"Cemento, gris\",saco,cemento y agregados,\"Cemex \"\"Tolteca\"\"\",\"Depot, S.A.\",\"Sur, CDMX\","
                + "\"1200.00\",2026-10-20,\"A,1\"\r\n";
        upload(owner, csv, "false").andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].material").value("Cemento, gris"))
                .andExpect(jsonPath("$.rows[0].supplier").value("Depot, S.A."))
                .andExpect(jsonPath("$.rows[0].branch").value("Sur, CDMX"))
                .andExpect(jsonPath("$.rows[0].price").value("1200.00"));
        assertEquals("Cemex \"Tolteca\"", jdbc.queryForObject(
                "select brand from material where organization_id = ? and name = 'Cemento, gris'", String.class, orgId));
        assertEquals("A,1", jdbc.queryForObject(
                "select sku from price_observation where organization_id = ?", String.class, orgId));
    }

    @Test
    void archivedMatchesAreRowErrors() throws Exception {
        String supplier = Api.id(api.post(owner, base + "/suppliers", "{\"name\":\"Depot\",\"branch\":\"Sur\"}")
                .andExpect(status().isCreated()));
        api.post(owner, base + "/materials/" + cement + "/archive", null).andExpect(status().isOk());
        api.post(owner, base + "/suppliers/" + supplier + "/archive", null).andExpect(status().isOk());
        upload(owner, HEADER + "Cemento gris 50 kg,saco,acero,,depot,SUR,10,2026-10-20,\n", "false")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.rows[0].errors.length()").value(2))
                .andExpect(jsonPath("$.rows[0].errors[0].field").value("material"))
                .andExpect(jsonPath("$.rows[0].errors[0].code").value("ARCHIVED_MATERIAL"))
                .andExpect(jsonPath("$.rows[0].errors[1].field").value("supplier"))
                .andExpect(jsonPath("$.rows[0].errors[1].code").value("ARCHIVED_SUPPLIER"));
        assertEquals(0L, count("price_observation"));
    }

    @Test
    void aWrongHeaderIsRejectedNamingTheExpectedOne() throws Exception {
        upload(owner, "material,unit,category,brand,supplier,branch,observed_on,price,sku\nA,saco,acero,,D,,1,2026-10-20,\n",
                "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(containsString(
                        "material,unit,category,brand,supplier,branch,price,observed_on,sku")));
        upload(owner, "", "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        upload(owner, HEADER, "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedFilesAreRejected() throws Exception {
        upload(owner, HEADER + "\"open,saco\n", "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        byte[] latin1 = (HEADER + "Caño,pieza,plomeria,,D,,1,2026-10-20,\n").getBytes(StandardCharsets.ISO_8859_1);
        upload(owner, latin1, "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart(base + "/price-observations/import").param("dryRun", "true")
                        .cookie(owner.access(), owner.csrf().cookie()).header(owner.csrf().header(), owner.csrf().token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void filesOverOneMegabyteOrTwoThousandRowsAreRejected() throws Exception {
        String row = "Arena,m3,cemento y agregados,,Depot,,10,2026-10-20,\n";
        upload(owner, HEADER + row.repeat(2000), "true").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.rows").value(2000));
        upload(owner, HEADER + row.repeat(2001), "true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        byte[] big = (HEADER + "Arena,m3,acero,,Depot,,10,2026-10-20," + "x".repeat(1024 * 1024) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        upload(owner, big, "false").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertNothingWritten();
    }

    @Test
    void membersAreForbiddenStrangersSeeNotFoundAndCsrfIsRequired() throws Exception {
        Session member = api.signUp();
        new Members(txm, organizations, memberships, projects, accesses).add(orgId, member.user(), "MEMBER");
        upload(member, THREE_ROWS, "true").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        upload(member, THREE_ROWS, "false").andExpect(status().isForbidden());
        upload(api.signUp(), THREE_ROWS, "false").andExpect(status().isNotFound());
        mvc.perform(multipart(base + "/price-observations/import")
                        .file(new MockMultipartFile("file", "p.csv", "text/csv", THREE_ROWS.getBytes(StandardCharsets.UTF_8)))
                        .param("dryRun", "false").cookie(owner.access()))
                .andExpect(status().isForbidden());
        assertNothingWritten();
    }
}

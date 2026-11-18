package org.andre.construx.modules.pricing.controller;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.pricing.importing.SyntheticPresupuesto;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importing the PU Win+ Presupuesto workbook over HTTP (docs/pricing.md §4): presigned upload, the job and its
 * asynchronous dry run, confirm with the licence statement, cancel, one active job per company, and access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogImportFlowTest {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String CONFIRM = "{\"licenceAccepted\":true,\"statement\":\"LICENCE_V1\"}";

    @TestConfiguration
    static class Storage {
        @Bean
        @Primary
        ObjectStorage memoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired ObjectStorage storage;

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
    }

    private record Upload(String id, String key) {}

    private Upload slot(Session s, long size) throws Exception {
        String body = "{\"kind\":\"PRESUPUESTO\",\"fileName\":\"ConstruBASE_Presupuesto.xlsx\",\"contentType\":\""
                + XLSX + "\",\"sizeBytes\":" + size + "}";
        String json = api.post(s, base + "/catalog-imports/uploads", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.headers['Content-Type']").value(XLSX))
                .andExpect(jsonPath("$.expiresAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(json, "$.uploadUrl");
        return new Upload(JsonPath.read(json, "$.uploadId"), url.substring("memory://upload/".length()));
    }

    /** Uploads the workbook as the browser would and starts the job; returns its id. */
    private String start(byte[] workbook) throws Exception {
        Upload upload = slot(owner, workbook.length);
        assertThat(upload.key()).startsWith("orgs/" + orgId + "/catalog-imports/" + upload.id() + "/")
                .endsWith(".xlsx");
        storage.put(upload.key(), workbook, XLSX);
        return Api.id(api.post(owner, base + "/catalog-imports",
                "{\"presupuestoUploadId\":\"" + upload.id() + "\"}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.file.name").value("ConstruBASE_Presupuesto.xlsx"))
                .andExpect(jsonPath("$.file.sizeBytes").value(workbook.length))
                .andExpect(jsonPath("$.file.sha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.uploadedBy.membershipId", notNullValue())));
    }

    /** Polls the job as the dashboard does until it reaches {@code status}. */
    private String await(String importId, String expected) throws Exception {
        String json = null;
        for (int i = 0; i < 200; i++) {
            json = api.get(owner, base + "/catalog-imports/" + importId).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String current = JsonPath.read(json, "$.status");
            if (current.equals(expected)) {
                return json;
            }
            if (!current.equals("PENDING") && !current.equals("VALIDATING") && !current.equals("IMPORTING")) {
                break;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Import did not reach " + expected + ": " + json);
    }

    private String importAll(SyntheticPresupuesto wb) throws Exception {
        String id = start(wb.bytes());
        await(id, "VALIDATED");
        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isAccepted());
        await(id, "DONE");
        return id;
    }

    @Test
    void validatesThenImportsTheWorkbookAfterTheLicenceIsAccepted() throws Exception {
        String id = start(SyntheticPresupuesto.standard().bytes());
        String validated = await(id, "VALIDATED");
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.MATERIAL.new")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.WORK_ITEM.new")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(validated, "$.summary.units.new")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(validated, "$.summary.warnings.byCode.UNIT_NOT_LISTED")).isEqualTo(1);
        assertThat((String) JsonPath.read(validated, "$.validatedAt")).isNotNull();
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));

        for (String refused : new String[] {"{\"licenceAccepted\":false,\"statement\":\"LICENCE_V1\"}",
                "{\"licenceAccepted\":true,\"statement\":\"LICENCE_V0\"}", "{}"}) {
            api.post(owner, base + "/catalog-imports/" + id + "/confirm", refused).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LICENCE_NOT_ACCEPTED"));
        }
        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IMPORTING"))
                .andExpect(jsonPath("$.licence.statement").value("LICENCE_V1"))
                .andExpect(jsonPath("$.licence.acceptedBy.membershipId", notNullValue()))
                .andExpect(jsonPath("$.confirmedAt", notNullValue()));
        String done = await(id, "DONE");
        assertThat((String) JsonPath.read(done, "$.finishedAt")).isNotNull();
        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.counts.MATERIAL").value(4)).andExpect(jsonPath("$.counts.LABOR").value(3))
                .andExpect(jsonPath("$.counts.units").value(6)).andExpect(jsonPath("$.counts.suppliers").value(1));
        String workItem = api.get(owner, base + "/resources?type=WORK_ITEM").andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(workItem, "$.content[0].code")).isEqualTo("10301-001");
        assertThat((String) JsonPath.read(workItem, "$.content[0].currentCost")).isEqualTo("138.01");
        assertThat((String) JsonPath.read(workItem, "$.content[0].source")).isEqualTo("NEODATA_CONSTRUBASE");
        String laborId = JsonPath.read(api.get(owner, base + "/resources?query=MO011").andReturn().getResponse()
                .getContentAsString(), "$.content[0].id");
        api.get(owner, base + "/resources/" + laborId).andExpect(jsonPath("$.currentCost").value("592.70"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("IMPORTED"))
                .andExpect(jsonPath("$.laborDetail.nominalWage").value("350.00"))
                .andExpect(jsonPath("$.history[0].source").value("NEODATA_CONSTRUBASE"))
                .andExpect(jsonPath("$.history[0].importId").value(id))
                .andExpect(jsonPath("$.history[0].recordedBy", nullValue()));
        assertThat(jdbc.queryForObject("select count(*) from resource_cost_history where import_id = ?", Integer.class,
                UUID.fromString(id))).isEqualTo(6);
        assertThat(jdbc.queryForObject("select last_import_id from catalog where organization_id = ?", UUID.class,
                orgId)).isEqualTo(UUID.fromString(id));
        assertThat(jdbc.queryForObject("select count(*) from equipment_operation_line where organization_id = ?",
                Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from equipment_standby_percentage where organization_id = ?",
                Integer.class, orgId)).isEqualTo(1);
        // no FASAR workbook: a PARTIAL set from N_Campos Generales, percentages divided by 100 (200 % -> 2 %)
        api.get(owner, base + "/fasar").andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.source").value("GENERAL_FIELDS"))
                .andExpect(jsonPath("$.minimumWage").value("248.93"));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.fasar.status").value("PARTIAL"));
        assertThat(jdbc.queryForObject("select c.employer_rate from fasar_contribution c join fasar_parameter_set s "
                + "on s.id = c.set_id where s.organization_id = ? and c.concept = 'RETIREMENT'", java.math.BigDecimal.class,
                orgId)).isEqualByComparingTo("0.02");
    }

    @Test
    void aSecondImportMergesByCodeAndFlagsMissingCodes() throws Exception {
        importAll(SyntheticPresupuesto.standard());
        SyntheticPresupuesto next = SyntheticPresupuesto.standard();
        next.sheet("CatalogoGeneral").set("301-CEM-0001", "Costo0", 250).removeRows("EQ001")
                .row("301-GRA-0001", "Grava", "Grava de rio", "M3", 1, "301", 0, 0, 0, null, null, null, null, 0, null,
                        0, 0, 310, null);
        next.sheet("DetalleMaquinaria").removeRows("EQ001");
        next.sheet("OperacionCH").removeRows("EQ001");
        next.sheet("ConsumosCH").removeRows("EQ001");

        String id = start(next.bytes());
        String validated = await(id, "VALIDATED");
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.MATERIAL.new")).isEqualTo(1);
        // the cement and the mortar that uses it: 7 x 250.00 + 319.00 = 2069.00
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.MATERIAL.costChanged")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.MATERIAL.unchanged")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(validated, "$.summary.resources.EQUIPMENT.missingFromFile")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(validated, "$.summary.units.unchanged")).isEqualTo(6);
        assertThat((String) JsonPath.read(validated, "$.summary.missingFromFile.sample[0].resourceCode"))
                .isEqualTo("EQ001");
        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isAccepted());
        await(id, "DONE");

        String eq = JsonPath.read(api.get(owner, base + "/resources?query=EQ001").andReturn().getResponse()
                .getContentAsString(), "$.content[0].id");
        api.get(owner, base + "/resources/" + eq).andExpect(jsonPath("$.missingFromImportAt", notNullValue()));
        String cement = JsonPath.read(api.get(owner, base + "/resources?query=301-CEM").andReturn().getResponse()
                .getContentAsString(), "$.content[0].id");
        api.get(owner, base + "/resources/" + cement).andExpect(jsonPath("$.currentCost").value("250.00"))
                .andExpect(jsonPath("$.history.length()").value(2));
        api.get(owner, base + "/resources?query=MORT-1").andExpect(jsonPath("$.content[0].currentCost").value("2069.00"));
        // and the work item over it: 0.03 x 2069.00 = 62.07; + 74.64 + 2.24 = 138.95
        api.get(owner, base + "/resources?type=WORK_ITEM").andExpect(jsonPath("$.content[0].currentCost").value("138.95"));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.counts.MATERIAL").value(5));
    }

    @Test
    void aBrokenWorkbookFailsWithItsLocationAndWritesNothing() throws Exception {
        SyntheticPresupuesto wb = SyntheticPresupuesto.standard();
        wb.sheet("Matrices").dropColumn("CodigoI");
        String id = start(wb.bytes());
        String failed = await(id, "FAILED");
        assertThat((String) JsonPath.read(failed, "$.failure.code")).isEqualTo("MISSING_COLUMN");
        assertThat((String) JsonPath.read(failed, "$.failure.sheet")).isEqualTo("Matrices");
        assertThat((Integer) JsonPath.read(failed, "$.failure.row")).isEqualTo(1);
        assertThat((String) JsonPath.read(failed, "$.failure.column")).isEqualTo("CodigoI");
        assertThat((String) JsonPath.read(failed, "$.failure.message")).contains("Matrices").contains("CodigoI");
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));
        // a failed job no longer blocks a new one
        start(SyntheticPresupuesto.standard().bytes());
    }

    @Test
    void onlyOneJobIsActiveAndAValidatedJobCanBeCancelled() throws Exception {
        String first = start(SyntheticPresupuesto.standard().bytes());
        await(first, "VALIDATED");
        byte[] again = SyntheticPresupuesto.standard().bytes();
        Upload upload = slot(owner, again.length);
        storage.put(upload.key(), again, XLSX);
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + upload.id() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_IN_PROGRESS"))
                .andExpect(jsonPath("$.activeImportId").value(first));

        api.post(owner, base + "/catalog-imports/" + first + "/cancel", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        api.post(owner, base + "/catalog-imports/" + first + "/cancel", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        api.post(owner, base + "/catalog-imports/" + first + "/confirm", CONFIRM).andExpect(status().isConflict());
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + upload.id() + "\"}")
                .andExpect(status().isAccepted());
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void aWorkbookChangedAfterValidationIsNotImported() throws Exception {
        byte[] workbook = SyntheticPresupuesto.standard().bytes();
        Upload upload = slot(owner, workbook.length);
        storage.put(upload.key(), workbook, XLSX);
        String id = Api.id(api.post(owner, base + "/catalog-imports",
                "{\"presupuestoUploadId\":\"" + upload.id() + "\"}").andExpect(status().isAccepted()));
        await(id, "VALIDATED");
        SyntheticPresupuesto changed = SyntheticPresupuesto.standard();
        changed.sheet("CatalogoGeneral").set("MO011", "Costo0", 1);
        storage.put(upload.key(), changed.bytes(), XLSX);
        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isAccepted());
        String failed = await(id, "FAILED");
        assertThat((String) JsonPath.read(failed, "$.failure.code")).isEqualTo("FILE_CHANGED");
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void uploadsFollowTheFileRules() throws Exception {
        String uploads = base + "/catalog-imports/uploads";
        api.post(owner, uploads, "{\"kind\":\"PRESUPUESTO\",\"fileName\":\"p.xls\",\"contentType\":"
                        + "\"application/vnd.ms-excel\",\"sizeBytes\":10}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_TYPE"));
        api.post(owner, uploads, "{\"kind\":\"PRESUPUESTO\",\"fileName\":\"p.csv\",\"contentType\":\"" + XLSX
                + "\",\"sizeBytes\":10}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_TYPE"));
        api.post(owner, uploads, "{\"kind\":\"PRESUPUESTO\",\"fileName\":\"p.xlsx\",\"contentType\":\"" + XLSX
                + "\",\"sizeBytes\":52428801}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_TOO_LARGE"));
        api.post(owner, uploads, "{\"kind\":\"FASAR\",\"fileName\":\"f.xls\",\"contentType\":"
                + "\"application/vnd.ms-excel\",\"sizeBytes\":10}").andExpect(status().isCreated());

        Upload missing = slot(owner, 100);
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + missing.id() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_UPLOAD_MISSING"));
        storage.put(missing.key(), new byte[50], XLSX);
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + missing.id() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_UPLOAD_MISMATCH"));
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isNotFound());

        String id = start(SyntheticPresupuesto.standard().bytes());
        await(id, "VALIDATED");
        String consumed = jdbc.queryForObject("select id from catalog_import_upload where file_key = "
                + "(select file_key from catalog_import where id = ?)", String.class, UUID.fromString(id));
        api.post(owner, base + "/catalog-imports/" + id + "/cancel", null).andExpect(status().isOk());
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + consumed + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void importsNeedManagePricesAndStayInsideTheOrganization() throws Exception {
        String id = start(SyntheticPresupuesto.standard().bytes());
        await(id, "VALIDATED");

        Session worker = api.signUp();
        members.add(orgId, worker.user(), "WORKER");
        api.post(worker, base + "/catalog-imports/uploads", "{\"kind\":\"PRESUPUESTO\",\"fileName\":\"p.xlsx\","
                + "\"contentType\":\"" + XLSX + "\",\"sizeBytes\":10}").andExpect(status().isForbidden());
        api.get(worker, base + "/catalog-imports/" + id).andExpect(status().isForbidden());
        api.post(worker, base + "/catalog-imports/" + id + "/cancel", null).andExpect(status().isForbidden());

        Session stranger = api.signUp();
        api.get(stranger, base + "/catalog-imports/" + id).andExpect(status().isNotFound());
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String other = "/api/organizations/" + otherOrg;
        api.get(stranger, other + "/catalog-imports/" + id).andExpect(status().isNotFound());
        api.post(stranger, other + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isNotFound());
        api.post(stranger, other + "/catalog-imports/" + id + "/cancel", null).andExpect(status().isNotFound());
        api.get(owner, base + "/catalog-imports/" + id).andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    private static final String XLS = "application/vnd.ms-excel";

    /** Uploads both workbooks and starts the job with the FASAR one; returns its id. */
    private String startWithFasar(byte[] presupuesto, byte[] fasar) throws Exception {
        Upload p = slot(owner, presupuesto.length);
        storage.put(p.key(), presupuesto, XLSX);
        String json = api.post(owner, base + "/catalog-imports/uploads", "{\"kind\":\"FASAR\",\"fileName\":"
                        + "\"ConstruBASE_FasarEstandar.xls\",\"contentType\":\"" + XLS + "\",\"sizeBytes\":"
                        + fasar.length + "}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String fasarId = JsonPath.read(json, "$.uploadId");
        String url = JsonPath.read(json, "$.uploadUrl");
        assertThat(url).endsWith(".xls");
        storage.put(url.substring("memory://upload/".length()), fasar, XLS);
        return Api.id(api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + p.id()
                        + "\",\"fasarUploadId\":\"" + fasarId + "\"}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fasarFile.name").value("ConstruBASE_FasarEstandar.xls"))
                .andExpect(jsonPath("$.fasarFile.sizeBytes").value(fasar.length))
                .andExpect(jsonPath("$.fasarFile.sha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}"))));
    }

    private String resourceId(String code) throws Exception {
        return JsonPath.read(api.get(owner, base + "/resources?query=" + code).andReturn().getResponse()
                .getContentAsString(), "$.content[0].id");
    }

    /**
     * The synthetic FASAR parameters give MO011 (Sn 350) FSR 1.723660 and real wage 603.28, MO020 (Sn 500)
     * 1.706615 and 853.31 (worked out in ImportPlanTest); the gang 1456.59 and the work item 136.14.
     */
    @Test
    void aFasarWorkbookReplacesTheParametersAndRecomputesTheLaborCategories() throws Exception {
        org.andre.construx.modules.pricing.importing.SyntheticFasar fasar =
                new org.andre.construx.modules.pricing.importing.SyntheticFasar();
        fasar.year = 2019;
        fasar.salaries.get(1)[4] = 1.706615;
        fasar.salaries.get(1)[5] = 853.31;
        fasar.salaries.add(new Object[] {"MO999", "SOLDADOR", 600, 1.04, 1.7, 1020.0});
        String id = startWithFasar(SyntheticPresupuesto.standard().bytes(), fasar.bytes());
        String validated = await(id, "VALIDATED");
        assertThat((Boolean) JsonPath.read(validated, "$.summary.fasar.provided")).isTrue();
        assertThat((Integer) JsonPath.read(validated, "$.summary.fasar.year")).isEqualTo(2019);
        assertThat((String) JsonPath.read(validated, "$.summary.fasar.uma")).isEqualTo("100.0000");
        assertThat((String) JsonPath.read(validated, "$.summary.fasar.minimumWage")).isEqualTo("200.00");
        assertThat((Integer) JsonPath.read(validated, "$.summary.fasar.laborCategories")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(validated, "$.summary.fasar.fsrMismatches")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(validated, "$.summary.fasar.realWageMismatches")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(validated, "$.summary.warnings.byCode.FASAR_YEAR_OLD")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(validated, "$.summary.warnings.byCode.FASAR_CATEGORY_NOT_IN_CATALOG"))
                .isEqualTo(1);
        // nothing is written by the dry run
        api.get(owner, base + "/fasar").andExpect(jsonPath("$.status").value("NONE"));

        api.post(owner, base + "/catalog-imports/" + id + "/confirm", CONFIRM).andExpect(status().isAccepted());
        await(id, "DONE");
        api.get(owner, base + "/fasar").andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.source").value("FASAR_FILE")).andExpect(jsonPath("$.year").value(2019))
                .andExpect(jsonPath("$.uma").value("100.0000")).andExpect(jsonPath("$.minimumWage").value("200.00"))
                .andExpect(jsonPath("$.days.workedSundays").value("4"))
                .andExpect(jsonPath("$.laborCategories.computed").value(2))
                .andExpect(jsonPath("$.laborCategories.mismatches").value(1))
                .andExpect(jsonPath("$.updatedBy.membershipId", notNullValue()));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.fasar.status").value("COMPLETE"));
        api.get(owner, base + "/resources/" + resourceId("MO011")).andExpect(jsonPath("$.currentCost").value("603.28"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("COMPUTED"))
                .andExpect(jsonPath("$.laborDetail.fsr").value("1.72366"))
                .andExpect(jsonPath("$.laborDetail.fsrMismatch").value(true))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].importId").value(id));
        api.get(owner, base + "/resources/" + resourceId("MO020")).andExpect(jsonPath("$.currentCost").value("853.31"))
                .andExpect(jsonPath("$.laborDetail.fsrMismatch").value(false));
        api.get(owner, base + "/resources?query=1A").andExpect(jsonPath("$.content[0].currentCost").value("1456.59"));
        api.get(owner, base + "/resources?type=WORK_ITEM").andExpect(jsonPath("$.content[0].currentCost").value("136.14"));

        // a later import without the FASAR workbook keeps the complete set and computes again
        String again = importAll(SyntheticPresupuesto.standard());
        assertThat((Boolean) JsonPath.read(api.get(owner, base + "/catalog-imports/" + again).andReturn()
                .getResponse().getContentAsString(), "$.summary.fasar.provided")).isFalse();
        api.get(owner, base + "/fasar").andExpect(jsonPath("$.source").value("FASAR_FILE"))
                .andExpect(jsonPath("$.version").value(1));
        api.get(owner, base + "/resources/" + resourceId("MO011")).andExpect(jsonPath("$.currentCost").value("603.28"))
                .andExpect(jsonPath("$.laborDetail.fsrSource").value("COMPUTED"));
    }

    @Test
    void aBrokenFasarWorkbookFailsTheImport() throws Exception {
        org.andre.construx.modules.pricing.importing.SyntheticFasar fasar =
                new org.andre.construx.modules.pricing.importing.SyntheticFasar();
        fasar.withoutSheets.add("d)Salarios");
        String id = startWithFasar(SyntheticPresupuesto.standard().bytes(), fasar.bytes());
        String failed = await(id, "FAILED");
        assertThat((String) JsonPath.read(failed, "$.failure.code")).isEqualTo("FASAR_MISSING_SHEET");
        assertThat((String) JsonPath.read(failed, "$.failure.sheet")).isEqualTo("d)Salarios");
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void theFasarUploadMustBeAFasarSlotOfTheOrganization() throws Exception {
        byte[] workbook = SyntheticPresupuesto.standard().bytes();
        Upload p = slot(owner, workbook.length);
        storage.put(p.key(), workbook, XLSX);
        Upload other = slot(owner, workbook.length);
        storage.put(other.key(), workbook, XLSX);
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + p.id() + "\",\"fasarUploadId\":\""
                + other.id() + "\"}").andExpect(status().isNotFound());
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + p.id() + "\",\"fasarUploadId\":\""
                + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        String json = api.post(owner, base + "/catalog-imports/uploads", "{\"kind\":\"FASAR\",\"fileName\":"
                + "\"f.xls\",\"contentType\":\"" + XLS + "\",\"sizeBytes\":10}").andReturn().getResponse()
                .getContentAsString();
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + p.id() + "\",\"fasarUploadId\":\""
                + JsonPath.read(json, "$.uploadId") + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_UPLOAD_MISSING"));
        // nothing was consumed: the Presupuesto alone still starts
        api.post(owner, base + "/catalog-imports", "{\"presupuestoUploadId\":\"" + p.id() + "\"}")
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.fasarFile", nullValue()));
    }
}

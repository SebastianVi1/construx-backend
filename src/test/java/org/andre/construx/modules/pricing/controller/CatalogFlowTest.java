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

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalog summary, Units and Families (docs/pricing.md §2.1, §2.2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String base;
    private CatalogRows rows;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        rows = new CatalogRows(jdbc, orgId);
    }

    @Test
    void anOrganizationWithoutACatalogReadsAnEmptyOne() throws Exception {
        api.get(owner, base + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.currency").value(nullValue()))
                .andExpect(jsonPath("$.counts.MATERIAL").value(0))
                .andExpect(jsonPath("$.counts.LABOR").value(0))
                .andExpect(jsonPath("$.counts.EQUIPMENT").value(0))
                .andExpect(jsonPath("$.counts.WORK_ITEM").value(0))
                .andExpect(jsonPath("$.counts.suppliers").value(0))
                .andExpect(jsonPath("$.counts.units").value(0))
                .andExpect(jsonPath("$.counts.families").value(0))
                .andExpect(jsonPath("$.counts.length()").value(7))
                .andExpect(jsonPath("$.missingFromImport").value(0))
                .andExpect(jsonPath("$.lastImport").value(nullValue()))
                .andExpect(jsonPath("$.activeImport").value(nullValue()))
                .andExpect(jsonPath("$.fasar.status").value("NONE"))
                .andExpect(jsonPath("$.fasar.year").value(nullValue()));
    }

    @Test
    void theFirstSupplierCreatesTheCatalog() throws Exception {
        api.post(owner, base + "/suppliers", "{\"name\":\"Depot\"}").andExpect(status().isCreated());
        api.post(owner, base + "/suppliers", "{\"name\":\"Ferre\"}").andExpect(status().isCreated());

        api.get(owner, base + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.counts.suppliers").value(2))
                .andExpect(jsonPath("$.counts.MATERIAL").value(0))
                .andExpect(jsonPath("$.counts.LABOR").value(0))
                .andExpect(jsonPath("$.counts.EQUIPMENT").value(0))
                .andExpect(jsonPath("$.counts.WORK_ITEM").value(0))
                .andExpect(jsonPath("$.counts.units").value(0))
                .andExpect(jsonPath("$.counts.families").value(0));
        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbc.queryForObject(
                "select count(*) from catalog where organization_id = ?", Long.class, orgId));
    }

    @Test
    void theSummaryCountsTheCatalogsRowsWithoutArchivedOnes() throws Exception {
        rows.catalog();
        UUID m3 = rows.unit("M3", "Metro cúbico");
        UUID jor = rows.unit("JOR", "Jornada");
        rows.archive("unit", rows.unit("OLD", null));
        rows.family("301", "AGREGADOS");
        rows.supplier("Depot");
        rows.archive("supplier", rows.supplier("Cerrado"));
        rows.resource("MAT-1", "MATERIAL", "Arena", m3).insert();
        rows.resource("MAT-2", "MATERIAL", "Grava", m3).missingFromImport().insert();
        rows.resource("MAT-3", "MATERIAL", "Archivado", m3).archived().insert();
        rows.resource("MAT-4", "MATERIAL", "Archivado y ausente", m3).missingFromImport().archived().insert();
        rows.resource("MO-1", "LABOR", "Peón", jor).insert();
        rows.resource("CUAD-1", "LABOR", "Cuadrilla", jor).composite().insert();
        rows.resource("PU-1", "WORK_ITEM", "Muro", m3).composite().insert();

        api.get(owner, base + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.counts.MATERIAL").value(2))
                .andExpect(jsonPath("$.counts.LABOR").value(2))
                .andExpect(jsonPath("$.counts.EQUIPMENT").value(0))
                .andExpect(jsonPath("$.counts.WORK_ITEM").value(1))
                .andExpect(jsonPath("$.counts.suppliers").value(1))
                .andExpect(jsonPath("$.counts.units").value(2))
                .andExpect(jsonPath("$.counts.families").value(1))
                .andExpect(jsonPath("$.missingFromImport").value(1))
                .andExpect(jsonPath("$.fasar.status").value("NONE"));
    }

    @Test
    void unitsAreOrderedByCodeWithTheirResourceCount() throws Exception {
        UUID m3 = rows.unit("M3", "Metro cúbico");
        rows.unit("KG", "Kilogramo");
        UUID old = rows.unit("BOLSA", null);
        rows.archive("unit", old);
        rows.resource("MAT-1", "MATERIAL", "Arena", m3).insert();
        rows.resource("MAT-2", "MATERIAL", "Grava", m3).insert();

        api.get(owner, base + "/units").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("KG", "M3")))
                .andExpect(jsonPath("$[0].resourceCount").value(0))
                .andExpect(jsonPath("$[1].id").value(m3.toString()))
                .andExpect(jsonPath("$[1].description").value("Metro cúbico"))
                .andExpect(jsonPath("$[1].source").value("SEED"))
                .andExpect(jsonPath("$[1].archivedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].resourceCount").value(2));
        api.get(owner, base + "/units?includeArchived=true").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("BOLSA", "KG", "M3")))
                .andExpect(jsonPath("$[0].archivedAt").isNotEmpty());
    }

    @Test
    void familiesAreOrderedByCodeWithTheirResourceCount() throws Exception {
        UUID m3 = rows.unit("M3", null);
        UUID agregados = rows.family("301", "AGREGADOS");
        rows.family("100", "ACERO");
        rows.archive("family", rows.family("000", "VIEJA"));
        rows.resource("MAT-1", "MATERIAL", "Arena", m3).family(agregados).insert();

        api.get(owner, base + "/families").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].code", contains("100", "301")))
                .andExpect(jsonPath("$[1].name").value("AGREGADOS"))
                .andExpect(jsonPath("$[1].source").value("MANUAL"))
                .andExpect(jsonPath("$[1].resourceCount").value(1))
                .andExpect(jsonPath("$[0].resourceCount").value(0));
        api.get(owner, base + "/families?includeArchived=true").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("000", "100", "301")));
    }

    @Test
    void anotherOrganizationsUnitsAndCatalogStayHidden() throws Exception {
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        CatalogRows theirs = new CatalogRows(jdbc, otherOrg);
        theirs.catalog();
        UUID unit = theirs.unit("M3", null);
        theirs.family("301", "AGREGADOS");
        theirs.resource("MAT-1", "MATERIAL", "Arena", unit).insert();

        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.counts.MATERIAL").value(0));
        api.get(owner, base + "/units").andExpect(jsonPath("$", hasSize(0)));
        api.get(owner, base + "/families").andExpect(jsonPath("$", hasSize(0)));
        api.get(owner, "/api/organizations/" + otherOrg + "/catalog").andExpect(status().isNotFound());
        api.get(owner, "/api/organizations/" + otherOrg + "/units").andExpect(status().isNotFound());
    }

    @Test
    void unitAndFamilyCountsIncludeArchivedResources() throws Exception {
        UUID unit = rows.unit("PZA", "Piece");
        UUID family = rows.family("F", "Family");
        rows.resource("A", "MATERIAL", "Active", unit).family(family).insert();
        rows.resource("B", "MATERIAL", "Archived", unit).family(family).archived().insert();
        api.get(owner, base + "/units").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resourceCount").value(2));
        api.get(owner, base + "/families").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resourceCount").value(2));
    }

    @Test
    void summaryExcludesArchivedRowsForEveryResourceTypeAndFamily() throws Exception {
        rows.catalog();
        UUID unit = rows.unit("PZA", null);
        rows.family("ACTIVE", "Active");
        rows.archive("family", rows.family("ARCHIVED", "Archived"));
        for (String type : new String[] {"MATERIAL", "LABOR", "EQUIPMENT", "WORK_ITEM"}) {
            rows.resource(type + "-A", type, "Active", unit).insert();
            rows.resource(type + "-B", type, "Archived", unit).archived().insert();
        }
        api.get(owner, base + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.MATERIAL").value(1))
                .andExpect(jsonPath("$.counts.LABOR").value(1))
                .andExpect(jsonPath("$.counts.EQUIPMENT").value(1))
                .andExpect(jsonPath("$.counts.WORK_ITEM").value(1))
                .andExpect(jsonPath("$.counts.families").value(1));
    }

}

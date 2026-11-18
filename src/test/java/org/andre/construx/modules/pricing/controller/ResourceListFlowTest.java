package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /resources: page shape, filters and sort (docs/pricing.md §0, §2.4). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceListFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private String base;
    private CatalogRows rows;
    private UUID m3;
    private UUID jor;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        UUID orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        rows = new CatalogRows(jdbc, orgId);
        rows.catalog();
        m3 = rows.unit("M3", "Metro cúbico");
        jor = rows.unit("JOR", "Jornada");
    }

    private ResultActions list(String query) throws Exception {
        return api.get(owner, base + "/resources" + query);
    }

    @Test
    void rowsComeAsResourceResponsesInsideAPage() throws Exception {
        UUID agregados = rows.family("301", "AGREGADOS");
        UUID varios = rows.supplier("VARIOS");
        UUID arena = rows.resource("301-ARE-0101", "MATERIAL", "Arena de mina", m3).family(agregados)
                .supplier(varios).insert();
        rows.cost(arena, "290", Instant.parse("2026-11-15T18:02:11Z"), null, null);
        rows.resource("MO011", "LABOR", "Peón", jor).insert();

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(arena.toString()))
                .andExpect(jsonPath("$.content[0].code").value("301-ARE-0101"))
                .andExpect(jsonPath("$.content[0].type").value("MATERIAL"))
                .andExpect(jsonPath("$.content[0].name").value("Arena de mina"))
                .andExpect(jsonPath("$.content[0].unit.id").value(m3.toString()))
                .andExpect(jsonPath("$.content[0].unit.code").value("M3"))
                .andExpect(jsonPath("$.content[0].family.id").value(agregados.toString()))
                .andExpect(jsonPath("$.content[0].family.code").value("301"))
                .andExpect(jsonPath("$.content[0].family.name").value("AGREGADOS"))
                .andExpect(jsonPath("$.content[0].supplier.id").value(varios.toString()))
                .andExpect(jsonPath("$.content[0].supplier.name").value("VARIOS"))
                .andExpect(jsonPath("$.content[0].brand").value(nullValue()))
                .andExpect(jsonPath("$.content[0].isPercentage").value(false))
                .andExpect(jsonPath("$.content[0].isComposite").value(false))
                .andExpect(jsonPath("$.content[0].isHourlyCost").value(false))
                .andExpect(jsonPath("$.content[0].discontinued").value(false))
                .andExpect(jsonPath("$.content[0].currentCost").value("290.00"))
                .andExpect(jsonPath("$.content[0].costUpdatedAt").value("2026-11-15T18:02:11Z"))
                .andExpect(jsonPath("$.content[0].source").value("NEODATA_CONSTRUBASE"))
                .andExpect(jsonPath("$.content[0].missingFromImportAt").value(nullValue()))
                .andExpect(jsonPath("$.content[0].archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.content[1].code").value("MO011"))
                .andExpect(jsonPath("$.content[1].family").value(nullValue()))
                .andExpect(jsonPath("$.content[1].supplier").value(nullValue()))
                .andExpect(jsonPath("$.content[1].currentCost").value(nullValue()))
                .andExpect(jsonPath("$.content[1].costUpdatedAt").value(nullValue()));
    }

    @Test
    void pagesFollowTheRequestedPageAndSize() throws Exception {
        for (String code : new String[] {"E", "B", "D", "A", "C"}) {
            rows.resource(code, "MATERIAL", "R " + code, m3).insert();
        }
        list("?page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code", contains("C", "D")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        list("?page=3&size=2").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        list("?size=200").andExpect(status().isOk()).andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void filtersNarrowTheList() throws Exception {
        UUID acero = rows.family("100", "ACERO");
        UUID depot = rows.supplier("Depot");
        rows.resource("MAT-1", "MATERIAL", "Varilla", m3).family(acero).supplier(depot).insert();
        rows.resource("MAT-2", "MATERIAL", "Arena", m3).description("Arena de RÍO cribada").discontinued().insert();
        rows.resource("MAT-3", "MATERIAL", "Grava", m3).missingFromImport().insert();
        rows.resource("MAT-4", "MATERIAL", "Vieja", m3).archived().insert();
        rows.resource("MO-1", "LABOR", "Peón", jor).insert();
        rows.resource("CUAD-1", "LABOR", "Cuadrilla de albañil", jor).composite().insert();
        rows.resource("EQ-1", "EQUIPMENT", "Revolvedora", jor).insert();
        rows.resource("PU-1", "WORK_ITEM", "Muro de block", m3).composite().insert();

        list("").andExpect(jsonPath("$.totalElements").value(7));
        list("?type=LABOR&type=EQUIPMENT").andExpect(jsonPath("$.content[*].code", contains("CUAD-1", "EQ-1", "MO-1")));
        list("?type=LABOR&composite=true").andExpect(jsonPath("$.content[*].code", contains("CUAD-1")));
        list("?composite=false&type=LABOR").andExpect(jsonPath("$.content[*].code", contains("MO-1")));
        list("?query=mat-").andExpect(jsonPath("$.content[*].code", contains("MAT-1", "MAT-2", "MAT-3")));
        list("?query=  río ").andExpect(jsonPath("$.content[*].code", contains("MAT-2")));
        list("?query=ALBA").andExpect(jsonPath("$.content[*].code", contains("CUAD-1")));
        list("?query=100%25").andExpect(jsonPath("$.content", hasSize(0)));
        list("?familyId=" + acero).andExpect(jsonPath("$.content[*].code", contains("MAT-1")));
        list("?supplierId=" + depot).andExpect(jsonPath("$.content[*].code", contains("MAT-1")));
        list("?unitId=" + jor).andExpect(jsonPath("$.content[*].code", contains("CUAD-1", "EQ-1", "MO-1")));
        list("?discontinued=true").andExpect(jsonPath("$.content[*].code", contains("MAT-2")));
        list("?discontinued=false").andExpect(jsonPath("$.totalElements").value(6));
        list("?missingFromImport=true").andExpect(jsonPath("$.content[*].code", contains("MAT-3")));
        list("?includeArchived=true&type=MATERIAL")
                .andExpect(jsonPath("$.content[*].code", contains("MAT-1", "MAT-2", "MAT-3", "MAT-4")))
                .andExpect(jsonPath("$.content[3].archivedAt").isNotEmpty());
    }

    @Test
    void sortTakesOneKnownFieldAndADirection() throws Exception {
        UUID a = rows.resource("A", "MATERIAL", "zeta", m3).insert();
        UUID b = rows.resource("B", "MATERIAL", "Alfa", m3).insert();
        rows.resource("C", "MATERIAL", "media", m3).insert();
        rows.cost(a, "10.00", Instant.parse("2026-01-02T00:00:00Z"), null, null);
        rows.cost(b, "5.50", Instant.parse("2026-03-01T00:00:00Z"), null, null);

        list("?sort=code,desc").andExpect(jsonPath("$.content[*].code", contains("C", "B", "A")));
        list("?sort=name,asc").andExpect(jsonPath("$.content[*].code", contains("B", "C", "A")));
        list("?sort=name").andExpect(jsonPath("$.content[*].code", contains("B", "C", "A")));
        list("?sort=currentCost,asc").andExpect(jsonPath("$.content[*].code", contains("B", "A", "C")));
        list("?sort=costUpdatedAt,desc").andExpect(jsonPath("$.content[0].code").value("B"))
                .andExpect(jsonPath("$.content[1].code").value("A"));
    }

    @Test
    void anUnknownSortABadDirectionOrAnOutOfRangePageIsRejected() throws Exception {
        for (String query : new String[] {"?sort=price,asc", "?sort=code,up", "?sort=code,asc,name", "?sort=",
                "?size=0", "?size=201", "?page=-1"}) {
            list(query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void anotherOrganizationsResourcesNeverShow() throws Exception {
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        CatalogRows theirs = new CatalogRows(jdbc, otherOrg);
        UUID unit = theirs.unit("M3", null);
        UUID family = theirs.family("301", "AGREGADOS");
        theirs.resource("MAT-1", "MATERIAL", "Arena", unit).family(family).insert();
        rows.resource("MAT-9", "MATERIAL", "Mía", m3).insert();

        list("").andExpect(jsonPath("$.content[*].code", contains("MAT-9")));
        list("?familyId=" + family).andExpect(jsonPath("$.content", hasSize(0)));
        list("?unitId=" + unit).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @ParameterizedTest(name = "{0},{1} orders rows as {2}, {3}, {4}, {5}")
    @CsvSource({
            "code,asc,A,B,C,D", "code,desc,D,C,B,A",
            "name,asc,B,C,D,A", "name,desc,A,D,B,C",
            "currentCost,asc,B,C,A,D", "currentCost,desc,A,B,C,D",
            "costUpdatedAt,asc,A,B,C,D", "costUpdatedAt,desc,B,C,A,D"
    })
    void everySortDirectionUsesCodeTiesAndNullsLast(String field, String direction,
                                                  String first, String second, String third, String fourth) throws Exception {
        UUID a = rows.resource("A", "MATERIAL", "zeta", m3).insert();
        UUID c = rows.resource("C", "MATERIAL", "ALFA", m3).insert();
        UUID b = rows.resource("B", "MATERIAL", "alfa", m3).insert();
        rows.resource("D", "MATERIAL", "Media", m3).insert();
        rows.cost(a, "10.00", Instant.parse("2026-01-01T00:00:00Z"), null, null);
        rows.cost(c, "5.00", Instant.parse("2026-02-01T00:00:00Z"), null, null);
        rows.cost(b, "5.00", Instant.parse("2026-02-01T00:00:00Z"), null, null);

        list("?sort=" + field + "," + direction).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].code", contains(first, second, third, fourth)));
    }

    @Test
    void sizeOneKeepsTotalsAndOrderingAcrossTwoPages() throws Exception {
        rows.resource("B", "MATERIAL", "Second", m3).insert();
        rows.resource("A", "MATERIAL", "First", m3).insert();
        for (int page = 0; page < 2; page++) {
            list("?size=1&page=" + page).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].code", contains(page == 0 ? "A" : "B")))
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.size").value(1))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }
    }

    @Test
    void missingFromImportIsTriStateAndCombinesWithOtherFilters() throws Exception {
        UUID family = rows.family("F", "Family");
        UUID supplier = rows.supplier("Supplier");
        rows.resource("A", "MATERIAL", "Flagged", m3).family(family).supplier(supplier).missingFromImport().insert();
        rows.resource("B", "MATERIAL", "Unflagged", m3).family(family).supplier(supplier).insert();
        rows.resource("C", "EQUIPMENT", "Other type", m3).missingFromImport().insert();
        rows.resource("D", "MATERIAL", "Archived", m3).missingFromImport().archived().insert();

        list("").andExpect(jsonPath("$.content[*].code", contains("A", "B", "C")));
        list("?missingFromImport=true").andExpect(jsonPath("$.content[*].code", contains("A", "C")));
        list("?missingFromImport=false").andExpect(jsonPath("$.content[*].code", contains("B")));
        list("?missingFromImport=true&includeArchived=true")
                .andExpect(jsonPath("$.content[*].code", contains("A", "C", "D")));
        list("?type=MATERIAL&missingFromImport=true&discontinued=false&composite=false&familyId=" + family
                + "&unitId=" + m3 + "&supplierId=" + supplier + "&query=FLAGGED")
                .andExpect(jsonPath("$.content[*].code", contains("A")));
    }

    @ParameterizedTest(name = "invalid resource parameter {0}")
    @ValueSource(strings = {"page=abc", "page=1.5", "size=abc", "size=1.5", "type=UNKNOWN",
            "familyId=bad-id", "unitId=bad-id", "supplierId=bad-id", "composite=maybe",
            "includeArchived=maybe", "missingFromImport=maybe", "discontinued=maybe"})
    void malformedResourceParametersReturnAnInvalidArgumentProblem(String query) throws Exception {
        list("?" + query).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

}

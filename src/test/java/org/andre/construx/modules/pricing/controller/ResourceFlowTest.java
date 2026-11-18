package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading the Catalog: Resources paged, filtered and sorted, their detail and Cost history, the summary,
 * Units and Families (docs/pricing.md §0, §2.1, §2.2, §2.4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserProfileRepository profiles;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private String base;
    private CatalogSeed seed;
    private UUID bag;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        seed = new CatalogSeed(jdbc, orgId);
        seed.catalog();
        bag = seed.unit("SACO", "Saco");
    }

    @Test
    void listsResourcesOfTheRequestedTypeOnePageAtATimeOrderedByCode() throws Exception {
        UUID family = seed.family("301", "AGREGADOS");
        UUID supplier = seed.supplier("VARIOS");
        UUID cemento = seed.resource("MAT-002", "MATERIAL", "Cemento gris", bag);
        seed.set(cemento, "family_id", family);
        seed.set(cemento, "supplier_id", supplier);
        seed.set(cemento, "brand", "Cemex");
        seed.cost(cemento, "245.50", Instant.parse("2026-10-01T16:00:00Z"), "MANUAL", null, null, null, null);
        seed.resource("MAT-001", "MATERIAL", "Arena", bag);
        seed.resource("MAT-003", "MATERIAL", "Grava", bag);
        seed.resource("EQ-001", "EQUIPMENT", "Revolvedora", bag);

        api.get(owner, base + "/resources?type=MATERIAL&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].code").value("MAT-001"))
                .andExpect(jsonPath("$.content[1].id").value(cemento.toString()))
                .andExpect(jsonPath("$.content[1].type").value("MATERIAL"))
                .andExpect(jsonPath("$.content[1].name").value("Cemento gris"))
                .andExpect(jsonPath("$.content[1].unit.id").value(bag.toString()))
                .andExpect(jsonPath("$.content[1].unit.code").value("SACO"))
                .andExpect(jsonPath("$.content[1].family.code").value("301"))
                .andExpect(jsonPath("$.content[1].family.name").value("AGREGADOS"))
                .andExpect(jsonPath("$.content[1].supplier.id").value(supplier.toString()))
                .andExpect(jsonPath("$.content[1].supplier.name").value("VARIOS"))
                .andExpect(jsonPath("$.content[1].brand").value("Cemex"))
                .andExpect(jsonPath("$.content[1].isPercentage").value(false))
                .andExpect(jsonPath("$.content[1].isComposite").value(false))
                .andExpect(jsonPath("$.content[1].isHourlyCost").value(false))
                .andExpect(jsonPath("$.content[1].discontinued").value(false))
                .andExpect(jsonPath("$.content[1].currentCost").value("245.50"))
                .andExpect(jsonPath("$.content[1].costUpdatedAt").value("2026-10-01T16:00:00Z"))
                .andExpect(jsonPath("$.content[1].source").value("MANUAL"))
                .andExpect(jsonPath("$.content[1].missingFromImportAt").isEmpty())
                .andExpect(jsonPath("$.content[1].archivedAt").isEmpty());
        api.get(owner, base + "/resources?type=MATERIAL&size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].code").value("MAT-003"));
        api.get(owner, base + "/resources").andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].code").value("EQ-001"))
                .andExpect(jsonPath("$.content[0].family").isEmpty())
                .andExpect(jsonPath("$.content[0].currentCost").isEmpty());
        api.get(owner, base + "/resources?type=MATERIAL&type=EQUIPMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void searchesCodeNameAndDescriptionIgnoringCase() throws Exception {
        UUID arena = seed.resource("301-ARE-0101", "MATERIAL", "Arena de mina", bag);
        UUID cal = seed.resource("302-CAL-01", "MATERIAL", "Cal hidratada", bag);
        seed.set(cal, "description", "Bulto de 25 kg para ARENA y mortero");
        seed.resource("303-GRA", "MATERIAL", "Grava 3/4", bag);
        seed.resource("304-X_1", "MATERIAL", "Clavo 100%", bag);

        api.get(owner, base + "/resources?query=  arena ").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(arena.toString()))
                .andExpect(jsonPath("$.content[1].id").value(cal.toString()));
        api.get(owner, base + "/resources?query=are-01").andExpect(jsonPath("$.totalElements").value(1));
        api.get(owner, base + "/resources?query=100%").andExpect(jsonPath("$.totalElements").value(1));
        api.get(owner, base + "/resources?query=x_").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filtersByFamilyUnitSupplierArchivedDiscontinuedCompositeAndMissingFromImport() throws Exception {
        UUID m3 = seed.unit("M3", "Metro cúbico");
        UUID steel = seed.family("STEEL", "Acero");
        UUID depot = seed.supplier("Depot");
        UUID varilla = seed.resource("A-1", "MATERIAL", "Varilla", bag);
        seed.set(varilla, "family_id", steel);
        seed.set(varilla, "supplier_id", depot);
        UUID arena = seed.resource("A-2", "MATERIAL", "Arena", m3);
        UUID old = seed.resource("A-3", "MATERIAL", "Cemento viejo", bag);
        seed.set(old, "archived_at", Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        UUID gone = seed.resource("A-4", "MATERIAL", "Yeso", bag);
        seed.set(gone, "discontinued", true);
        seed.set(gone, "missing_from_import_at", Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
        UUID gang = seed.resource("CUAD-1", "LABOR", "Cuadrilla 1", bag);
        seed.set(gang, "is_composite", true);
        seed.resource("MO-1", "LABOR", "Peon", bag);

        api.get(owner, base + "/resources?familyId=" + steel).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(varilla.toString()));
        api.get(owner, base + "/resources?unitId=" + m3).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(arena.toString()));
        api.get(owner, base + "/resources?supplierId=" + depot).andExpect(jsonPath("$.totalElements").value(1));
        api.get(owner, base + "/resources?type=MATERIAL").andExpect(jsonPath("$.totalElements").value(3));
        api.get(owner, base + "/resources?type=MATERIAL&includeArchived=true")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[2].archivedAt").value("2026-10-01T00:00:00Z"));
        api.get(owner, base + "/resources?type=MATERIAL&discontinued=true")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(gone.toString()))
                .andExpect(jsonPath("$.content[0].discontinued").value(true));
        api.get(owner, base + "/resources?type=MATERIAL&discontinued=false")
                .andExpect(jsonPath("$.totalElements").value(2));
        api.get(owner, base + "/resources?missingFromImport=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].missingFromImportAt").value("2026-11-01T00:00:00Z"));
        api.get(owner, base + "/resources?type=LABOR&composite=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(gang.toString()))
                .andExpect(jsonPath("$.content[0].isComposite").value(true));
        api.get(owner, base + "/resources?type=LABOR&composite=false").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void sortsByNameCurrentCostAndLastCostUpdate() throws Exception {
        UUID a = seed.resource("C-1", "MATERIAL", "Zinc", bag);
        UUID b = seed.resource("C-2", "MATERIAL", "Arena", bag);
        UUID c = seed.resource("C-3", "MATERIAL", "Mortero", bag);
        seed.cost(a, "10.00", Instant.parse("2026-10-03T00:00:00Z"), "MANUAL", null, null, null, null);
        seed.cost(b, "300.00", Instant.parse("2026-10-01T00:00:00Z"), "MANUAL", null, null, null, null);
        seed.cost(c, "45.00", Instant.parse("2026-10-02T00:00:00Z"), "MANUAL", null, null, null, null);

        api.get(owner, base + "/resources?sort=name").andExpect(jsonPath("$.content[0].name").value("Arena"))
                .andExpect(jsonPath("$.content[2].name").value("Zinc"));
        api.get(owner, base + "/resources?sort=name,desc").andExpect(jsonPath("$.content[0].name").value("Zinc"));
        api.get(owner, base + "/resources?sort=currentCost,desc")
                .andExpect(jsonPath("$.content[0].currentCost").value("300.00"))
                .andExpect(jsonPath("$.content[1].currentCost").value("45.00"))
                .andExpect(jsonPath("$.content[2].currentCost").value("10.00"));
        api.get(owner, base + "/resources?sort=costUpdatedAt,asc").andExpect(jsonPath("$.content[0].code").value("C-2"))
                .andExpect(jsonPath("$.content[2].code").value("C-1"));
        api.get(owner, base + "/resources?sort=code,desc").andExpect(jsonPath("$.content[0].code").value("C-3"));
    }

    @Test
    void rejectsUnknownSortFieldsAndOutOfRangePages() throws Exception {
        for (String bad : new String[] {"sort=brand", "sort=name,up", "sort=name,asc,code", "size=0", "size=201",
                "page=-1"}) {
            api.get(owner, base + "/resources?" + bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        api.get(owner, base + "/resources?type=TOOL").andExpect(status().isBadRequest());
        api.get(owner, base + "/resources?size=200").andExpect(status().isOk());
    }

    @Test
    void detailShowsEveryFieldAndTheCostHistoryNewestFirst() throws Exception {
        UUID membership = members.ownerMembership(orgId, owner.user());
        profiles.save(new UserProfile(owner.user(), "Ana López"));
        UUID depot = seed.supplier("Depot");
        UUID cemento = seed.resource("MAT-001", "MATERIAL", "Cemento", bag);
        seed.set(cemento, "description", "Cemento gris 50 kg");
        seed.set(cemento, "reference", "REF-9");
        seed.set(cemento, "sap_code", "SAP-1");
        seed.set(cemento, "technical_sheet", "https://example.test/ficha.pdf");
        seed.set(cemento, "procedure", "Almacenar en seco");
        seed.set(cemento, "grouper", "G1");
        seed.set(cemento, "imported_cost", new BigDecimal("240.00"));
        seed.set(cemento, "imported_cost_on", Date.valueOf("2026-09-30"));
        UUID first = seed.cost(cemento, "230.00", Instant.parse("2026-09-01T18:00:00Z"), "CSV", depot,
                "C-50", null, null);
        UUID second = seed.cost(cemento, "245.50", Instant.parse("2026-10-01T16:00:00Z"), "MANUAL", null,
                null, "Precio de lista", membership);

        api.get(owner, base + "/resources/" + cemento).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cemento.toString()))
                .andExpect(jsonPath("$.code").value("MAT-001"))
                .andExpect(jsonPath("$.unit.code").value("SACO"))
                .andExpect(jsonPath("$.description").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.reference").value("REF-9"))
                .andExpect(jsonPath("$.sapCode").value("SAP-1"))
                .andExpect(jsonPath("$.technicalSheet").value("https://example.test/ficha.pdf"))
                .andExpect(jsonPath("$.procedure").value("Almacenar en seco"))
                .andExpect(jsonPath("$.grouper").value("G1"))
                .andExpect(jsonPath("$.importedCost").value("240.00"))
                .andExpect(jsonPath("$.importedCostOn").value("2026-09-30"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.currentCost").value("245.50"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].id").value(second.toString()))
                .andExpect(jsonPath("$.history[0].amount").value("245.50"))
                .andExpect(jsonPath("$.history[0].effectiveFrom").value("2026-10-01T16:00:00Z"))
                .andExpect(jsonPath("$.history[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId").value(membership.toString()))
                .andExpect(jsonPath("$.history[0].recordedBy.fullName").value("Ana López"))
                .andExpect(jsonPath("$.history[0].supplier").isEmpty())
                .andExpect(jsonPath("$.history[0].note").value("Precio de lista"))
                .andExpect(jsonPath("$.history[1].id").value(first.toString()))
                .andExpect(jsonPath("$.history[1].recordedBy").isEmpty())
                .andExpect(jsonPath("$.history[1].supplier.id").value(depot.toString()))
                .andExpect(jsonPath("$.history[1].supplier.name").value("Depot"))
                .andExpect(jsonPath("$.history[1].sku").value("C-50"))
                .andExpect(jsonPath("$.history[1].source").value("CSV"))
                .andExpect(jsonPath("$.usedIn.count").value(0));
        api.get(owner, base + "/resources/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void detailKeepsOnlyTheNewestHundredCostsAndTheCostsEndpointPagesThemAll() throws Exception {
        UUID cemento = seed.resource("MAT-001", "MATERIAL", "Cemento", bag);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 105; i++) {
            seed.cost(cemento, i + ".00", start.plusSeconds(3600L * i), "MANUAL", null, null, null, null);
        }

        api.get(owner, base + "/resources/" + cemento).andExpect(jsonPath("$.history.length()").value(100))
                .andExpect(jsonPath("$.history[0].amount").value("104.00"))
                .andExpect(jsonPath("$.history[99].amount").value("5.00"));
        api.get(owner, base + "/resources/" + cemento + "/costs?size=50&page=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(105))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[4].amount").value("0.00"));
        api.get(owner, base + "/resources/" + cemento + "/costs?from=2026-01-01T01:00:00Z&to=2026-01-01T03:00:00Z")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].amount").value("3.00"));
        api.get(owner, base + "/resources/" + cemento + "/costs?size=500").andExpect(status().isBadRequest());
    }

    @Test
    void catalogSummaryCountsTheCatalogAndSaysWhenThereIsNone() throws Exception {
        seed.family("STEEL", "Acero");
        seed.supplier("Depot");
        seed.resource("A-1", "MATERIAL", "Arena", bag);
        seed.resource("A-2", "MATERIAL", "Grava", bag);
        UUID archived = seed.resource("A-3", "MATERIAL", "Vieja", bag);
        seed.set(archived, "archived_at", Timestamp.from(Instant.now()));
        UUID missing = seed.resource("E-1", "EQUIPMENT", "Revolvedora", bag);
        seed.set(missing, "missing_from_import_at", Timestamp.from(Instant.now()));

        api.get(owner, base + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.counts.MATERIAL").value(2))
                .andExpect(jsonPath("$.counts.LABOR").value(0))
                .andExpect(jsonPath("$.counts.EQUIPMENT").value(1))
                .andExpect(jsonPath("$.counts.WORK_ITEM").value(0))
                .andExpect(jsonPath("$.counts.suppliers").value(1))
                .andExpect(jsonPath("$.counts.units").value(1))
                .andExpect(jsonPath("$.counts.families").value(1))
                .andExpect(jsonPath("$.missingFromImport").value(1))
                .andExpect(jsonPath("$.lastImport").isEmpty())
                .andExpect(jsonPath("$.fasar.status").value("NONE"))
                .andExpect(jsonPath("$.activeImport").isEmpty());

        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Empty " + UUID.randomUUID());
        api.get(other, "/api/organizations/" + otherOrg + "/catalog").andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.counts.MATERIAL").value(0))
                .andExpect(jsonPath("$.counts.suppliers").value(0))
                .andExpect(jsonPath("$.missingFromImport").value(0))
                .andExpect(jsonPath("$.currency").isEmpty())
                .andExpect(jsonPath("$.lastImport").isEmpty())
                .andExpect(jsonPath("$.fasar.status").value("NONE"));
    }

    @Test
    void unitsAndFamiliesAreListedByCodeWithTheirResourceCounts() throws Exception {
        UUID m3 = seed.unit("M3", "Metro cúbico");
        UUID kg = seed.unit("KG", null);
        jdbc.update("update unit set archived_at = ? where id = ?", Timestamp.from(Instant.now()), kg);
        UUID steel = seed.family("STEEL", "Acero");
        UUID wood = seed.family("WOOD", "Madera");
        jdbc.update("update family set archived_at = ? where id = ?", Timestamp.from(Instant.now()), wood);
        UUID a = seed.resource("A-1", "MATERIAL", "Arena", m3);
        seed.set(a, "family_id", steel);
        seed.resource("A-2", "MATERIAL", "Grava", m3);
        seed.resource("A-3", "MATERIAL", "Cal", bag);

        api.get(owner, base + "/units").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(m3.toString()))
                .andExpect(jsonPath("$[0].code").value("M3"))
                .andExpect(jsonPath("$[0].description").value("Metro cúbico"))
                .andExpect(jsonPath("$[0].source").value("SEED"))
                .andExpect(jsonPath("$[0].archivedAt").isEmpty())
                .andExpect(jsonPath("$[0].resourceCount").value(2))
                .andExpect(jsonPath("$[1].code").value("SACO"))
                .andExpect(jsonPath("$[1].resourceCount").value(1));
        api.get(owner, base + "/units?includeArchived=true").andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("KG"))
                .andExpect(jsonPath("$[0].resourceCount").value(0));
        api.get(owner, base + "/families").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("STEEL"))
                .andExpect(jsonPath("$[0].name").value("Acero"))
                .andExpect(jsonPath("$[0].resourceCount").value(1));
        api.get(owner, base + "/families?includeArchived=true").andExpect(jsonPath("$.length()").value(2));
    }
}

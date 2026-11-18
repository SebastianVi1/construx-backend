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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /resources/{id} and GET /resources/{id}/costs (docs/pricing.md §2.4, §2.5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceDetailFlowTest {
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
    private CatalogRows rows;
    private UUID m3;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        rows = new CatalogRows(jdbc, orgId);
        rows.catalog();
        m3 = rows.unit("M3", "Metro cúbico");
    }

    @Test
    void theDetailAddsTheLongFieldsHistoryNewestFirstAndWhereItIsUsed() throws Exception {
        api.put(owner, "/api/profile", "{\"fullName\":\"Ana Pérez\",\"phoneNumber\":\"+525512345678\"}")
                .andExpect(status().isOk());
        UUID ownerMembership = new Members(txm, organizations, memberships, projects, accesses)
                .ownerMembership(orgId, owner.user());
        UUID depot = rows.supplier("Depot");
        UUID arena = rows.resource("301-ARE-0101", "MATERIAL", "Arena de mina", m3)
                .description("Arena de mina cribada").supplier(depot).insert();
        jdbc.update("update resource set reference = 'REF-1', sap_code = 'SAP-1', technical_sheet = 'ficha.pdf', "
                + "procedure = 'Cribar', grouper = 'AG', imported_cost = 288.5, imported_cost_on = ? where id = ?",
                java.sql.Date.valueOf("2026-11-01"), arena);
        UUID first = rows.cost(arena, "280", Instant.parse("2026-10-01T15:00:00Z"), depot, null);
        UUID second = rows.cost(arena, "290", Instant.parse("2026-11-15T18:02:11Z"), null, ownerMembership);

        api.get(owner, base + "/resources/" + arena).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(arena.toString()))
                .andExpect(jsonPath("$.code").value("301-ARE-0101"))
                .andExpect(jsonPath("$.unit.code").value("M3"))
                .andExpect(jsonPath("$.supplier.name").value("Depot"))
                .andExpect(jsonPath("$.isComposite").value(false))
                .andExpect(jsonPath("$.currentCost").value("290.00"))
                .andExpect(jsonPath("$.description").value("Arena de mina cribada"))
                .andExpect(jsonPath("$.reference").value("REF-1"))
                .andExpect(jsonPath("$.sapCode").value("SAP-1"))
                .andExpect(jsonPath("$.technicalSheet").value("ficha.pdf"))
                .andExpect(jsonPath("$.procedure").value("Cribar"))
                .andExpect(jsonPath("$.grouper").value("AG"))
                .andExpect(jsonPath("$.importedCost").value("288.50"))
                .andExpect(jsonPath("$.importedCostOn").value("2026-11-01"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.history[*].id", contains(second.toString(), first.toString())))
                .andExpect(jsonPath("$.history[0].amount").value("290.00"))
                .andExpect(jsonPath("$.history[0].effectiveFrom").value("2026-11-15T18:02:11Z"))
                .andExpect(jsonPath("$.history[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId").value(ownerMembership.toString()))
                .andExpect(jsonPath("$.history[0].recordedBy.fullName").value("Ana Pérez"))
                .andExpect(jsonPath("$.history[0].supplier").value(nullValue()))
                .andExpect(jsonPath("$.history[0].note").value("nota 290"))
                .andExpect(jsonPath("$.history[0].importId").value(nullValue()))
                .andExpect(jsonPath("$.history[1].recordedBy").value(nullValue()))
                .andExpect(jsonPath("$.history[1].supplier.id").value(depot.toString()))
                .andExpect(jsonPath("$.history[1].supplier.name").value("Depot"))
                .andExpect(jsonPath("$.history[1].sku").value("SKU-280"))
                .andExpect(jsonPath("$.usedIn.count").value(0))
                .andExpect(jsonPath("$.usedIn.sample", hasSize(0)))
                .andExpect(jsonPath("$.analysis").doesNotExist())
                .andExpect(jsonPath("$.laborDetail").doesNotExist())
                .andExpect(jsonPath("$.equipmentDetail").doesNotExist());
    }

    @Test
    void theDetailShowsTheLatestHundredEntries() throws Exception {
        UUID arena = rows.resource("MAT-1", "MATERIAL", "Arena", m3).insert();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i <= 100; i++) {
            rows.cost(arena, i + ".00", start.plusSeconds(3600L * i), null, null);
        }
        api.get(owner, base + "/resources/" + arena).andExpect(status().isOk())
                .andExpect(jsonPath("$.history", hasSize(100)))
                .andExpect(jsonPath("$.history[0].amount").value("100.00"))
                .andExpect(jsonPath("$.history[99].amount").value("1.00"));
    }

    @Test
    void archivedResourcesAreStillReadable() throws Exception {
        UUID old = rows.resource("MAT-1", "MATERIAL", "Vieja", m3).archived().insert();
        api.get(owner, base + "/resources/" + old).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andExpect(jsonPath("$.history", hasSize(0)));
    }

    @Test
    void theCostsListIsAPageNewestFirstWithinOptionalBounds() throws Exception {
        UUID arena = rows.resource("MAT-1", "MATERIAL", "Arena", m3).insert();
        rows.cost(arena, "10", Instant.parse("2026-01-01T00:00:00Z"), null, null);
        rows.cost(arena, "20", Instant.parse("2026-02-01T00:00:00Z"), null, null);
        rows.cost(arena, "30", Instant.parse("2026-03-01T00:00:00Z"), null, null);
        String costs = base + "/resources/" + arena + "/costs";

        api.get(owner, costs).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("30.00", "20.00", "10.00")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        api.get(owner, costs + "?size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("10.00")))
                .andExpect(jsonPath("$.totalPages").value(2));
        api.get(owner, costs + "?from=2026-02-01T00:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("30.00", "20.00")));
        api.get(owner, costs + "?from=2026-01-15T00:00:00Z&to=2026-02-01T00:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("20.00")));
        api.get(owner, costs + "?size=201").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherOrganizationsResourceIsNotFound() throws Exception {
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        CatalogRows theirs = new CatalogRows(jdbc, otherOrg);
        UUID theirResource = theirs.resource("MAT-1", "MATERIAL", "Arena", theirs.unit("M3", null)).insert();
        theirs.cost(theirResource, "10", Instant.now(), null, null);

        api.get(owner, base + "/resources/" + theirResource).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        api.get(owner, base + "/resources/" + theirResource + "/costs").andExpect(status().isNotFound());
        api.get(owner, base + "/resources/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void costBoundsIncludeBothEndpointsAndIgnoreRequestedSort() throws Exception {
        UUID resource = rows.resource("A", "MATERIAL", "Resource", m3).insert();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 4; i++) {
            rows.cost(resource, (10 * i) + ".00", start.plusSeconds(i), null, null);
        }
        String costs = base + "/resources/" + resource + "/costs";
        api.get(owner, costs + "?from=2026-01-01T00:00:01Z&to=2026-01-01T00:00:02Z&sort=effectiveFrom,asc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("20.00", "10.00")))
                .andExpect(jsonPath("$.totalElements").value(2));
        api.get(owner, costs + "?from=2026-01-01T00:00:01Z&to=2026-01-01T00:00:01Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("10.00")));
        api.get(owner, costs + "?to=2026-01-01T00:00:01Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].amount", contains("10.00", "0.00")));
    }

    @Test
    void costsValidatePageSizeAndInstantParameters() throws Exception {
        UUID resource = rows.resource("A", "MATERIAL", "Resource", m3).insert();
        String costs = base + "/resources/" + resource + "/costs";
        for (String query : new String[] {"page=-1", "size=0", "size=201"}) {
            api.get(owner, costs + "?" + query).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        for (String query : new String[] {"from=bad", "to=bad", "page=1.5", "size=no"}) {
            api.get(owner, costs + "?" + query).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        for (int size : new int[] {1, 200}) {
            api.get(owner, costs + "?size=" + size).andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(size));
        }
    }

}

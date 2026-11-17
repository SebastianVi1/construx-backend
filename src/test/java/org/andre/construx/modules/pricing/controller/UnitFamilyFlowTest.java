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

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Units and Families management (docs/pricing.md §2.2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnitFamilyFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
    }

    @Test
    void creatingAUnitUpperCasesItsCodeAndCreatesTheCatalog() throws Exception {
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(false));
        api.post(owner, base + "/units", "{\"code\":\" m2 \",\"description\":\" Metro   cuadrado \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("M2"))
                .andExpect(jsonPath("$.description").value("Metro cuadrado"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.resourceCount").value(0));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.counts.units").value(1));
        api.get(owner, base + "/units").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("M2"));
    }

    @Test
    void aUnitCodeIsUniqueIgnoringCaseAndItsDescriptionCanBeChangedOrCleared() throws Exception {
        String id = Api.id(api.post(owner, base + "/units", "{\"code\":\"JOR\",\"description\":\"Jornal\"}"));
        api.post(owner, base + "/units", "{\"code\":\"jor\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_UNIT"));
        api.post(owner, base + "/units", "{\"code\":\" \"}").andExpect(status().isBadRequest());
        api.post(owner, base + "/units", "{\"code\":\"" + "X".repeat(21) + "\"}").andExpect(status().isBadRequest());
        api.patch(owner, base + "/units/" + id, "{\"description\":\"Jornada\",\"code\":\"HOR\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("JOR"))
                .andExpect(jsonPath("$.description").value("Jornada"));
        api.patch(owner, base + "/units/" + id, "{}").andExpect(jsonPath("$.description").value("Jornada"));
        api.patch(owner, base + "/units/" + id, "{\"description\":\"\"}")
                .andExpect(jsonPath("$.description").value(nullValue()));
        assertEquals(1L, jdbc.queryForObject("select count(*) from unit where organization_id = ?", Long.class, orgId));
    }

    @Test
    void archivingAUnitIsIdempotentAndHidesItFromTheDefaultList() throws Exception {
        String id = Api.id(api.post(owner, base + "/units", "{\"code\":\"HR\"}"));
        api.post(owner, base + "/units", "{\"code\":\"M3\"}");
        String first = api.post(owner, base + "/units/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String again = api.post(owner, base + "/units/" + id + "/archive", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, again);
        api.get(owner, base + "/units").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("M3"));
        api.get(owner, base + "/units?includeArchived=true").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("HR"));
    }

    @Test
    void resourceCountCountsTheResourcesUsingTheUnitOrFamily() throws Exception {
        CatalogSeed seed = new CatalogSeed(jdbc, orgId);
        seed.catalog();
        UUID unit = seed.unit("PZA", "Pieza");
        UUID family = seed.family("STEEL", "Acero");
        seed.set(seed.resource("MAT-1", "MATERIAL", "Varilla", unit), "family_id", family);
        seed.resource("MAT-2", "MATERIAL", "Clavo", unit);
        api.patch(owner, base + "/units/" + unit, "{\"description\":\"Piezas\"}")
                .andExpect(jsonPath("$.resourceCount").value(2));
        api.post(owner, base + "/families/" + family + "/archive", null)
                .andExpect(jsonPath("$.resourceCount").value(1));
    }

    @Test
    void familiesAreCreatedRenamedAndArchived() throws Exception {
        String id = Api.id(api.post(owner, base + "/families", "{\"code\":\" 301-are \",\"name\":\" Arena  fina \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("301-ARE"))
                .andExpect(jsonPath("$.name").value("Arena fina"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.resourceCount").value(0)));
        api.post(owner, base + "/families", "{\"code\":\"301-Are\",\"name\":\"Otra\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FAMILY"));
        api.post(owner, base + "/families", "{\"code\":\"X\"}").andExpect(status().isBadRequest());
        api.patch(owner, base + "/families/" + id, "{\"name\":\"Arenas\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Arenas")).andExpect(jsonPath("$.code").value("301-ARE"));
        api.patch(owner, base + "/families/" + id, "{\"name\":\" \"}").andExpect(status().isBadRequest());
        api.post(owner, base + "/families/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        api.post(owner, base + "/families/" + id + "/archive", null).andExpect(status().isOk());
        api.get(owner, base + "/families").andExpect(jsonPath("$.length()").value(0));
        api.get(owner, base + "/families?includeArchived=true").andExpect(jsonPath("$[0].name").value("Arenas"));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    void aMemberReadsButCannotWriteUnitsOrFamilies() throws Exception {
        String unit = Api.id(api.post(owner, base + "/units", "{\"code\":\"KG\"}"));
        String family = Api.id(api.post(owner, base + "/families", "{\"code\":\"F\",\"name\":\"F\"}"));
        Session member = api.signUp();
        new Members(txm, organizations, memberships, projects, accesses).add(orgId, member.user(), "MEMBER");
        api.get(member, base + "/units").andExpect(status().isOk());
        api.get(member, base + "/families").andExpect(status().isOk());
        api.post(member, base + "/units", "{\"code\":\"LT\"}").andExpect(status().isForbidden());
        api.patch(member, base + "/units/" + unit, "{\"description\":\"x\"}").andExpect(status().isForbidden());
        api.post(member, base + "/units/" + unit + "/archive", null).andExpect(status().isForbidden());
        api.post(member, base + "/families", "{\"code\":\"G\",\"name\":\"G\"}").andExpect(status().isForbidden());
        api.patch(member, base + "/families/" + family, "{\"name\":\"x\"}").andExpect(status().isForbidden());
        api.post(member, base + "/families/" + family + "/archive", null).andExpect(status().isForbidden());
        assertEquals(1L, jdbc.queryForObject("select count(*) from unit where organization_id = ?", Long.class, orgId));
    }

    @Test
    void anotherOrganizationsUnitOrFamilyIsNotFound() throws Exception {
        String unit = Api.id(api.post(owner, base + "/units", "{\"code\":\"KG\"}"));
        String family = Api.id(api.post(owner, base + "/families", "{\"code\":\"F\",\"name\":\"F\"}"));
        Session other = api.signUp();
        String otherBase = "/api/organizations/" + api.createOrganization(other, "Other " + UUID.randomUUID());
        api.patch(other, otherBase + "/units/" + unit, "{\"description\":\"x\"}").andExpect(status().isNotFound());
        api.post(other, otherBase + "/units/" + unit + "/archive", null).andExpect(status().isNotFound());
        api.patch(other, otherBase + "/families/" + family, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        api.post(other, otherBase + "/families/" + family + "/archive", null).andExpect(status().isNotFound());
        api.post(other, otherBase + "/units", "{\"code\":\"KG\"}").andExpect(status().isCreated());
        api.post(other, base + "/units", "{\"code\":\"LT\"}").andExpect(status().isNotFound());
    }
}

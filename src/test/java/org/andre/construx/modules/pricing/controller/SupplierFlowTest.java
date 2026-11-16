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

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierFlowTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private UUID orgId;
    private String suppliers;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        suppliers = "/api/organizations/" + orgId + "/suppliers";
    }

    private String create(String json) throws Exception {
        return Api.id(api.post(owner, suppliers, json).andExpect(status().isCreated()));
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from supplier where organization_id = ?", Long.class, orgId);
    }

    @Test
    void createReturnsTheCleanedSupplierAndTheListIsOrderedByName() throws Exception {
        String id = Api.id(api.post(owner, suppliers,
                        "{\"name\":\" Home   Depot \",\"branch\":\" Periférico Sur \",\"website\":\"https://www.homedepot.com.mx\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Home Depot"))
                .andExpect(jsonPath("$.branch").value("Periférico Sur"))
                .andExpect(jsonPath("$.website").value("https://www.homedepot.com.mx"))
                .andExpect(jsonPath("$.archivedAt").value(nullValue())));
        Map<String, Object> row = jdbc.queryForMap("select * from supplier where id = ?", UUID.fromString(id));
        assertEquals(orgId, row.get("organization_id"));
        assertEquals("Home Depot", row.get("name"));
        assertEquals("Periférico Sur", row.get("branch"));
        create("{\"name\":\"Ferretería Lupita\"}");
        api.get(owner, suppliers).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Ferretería Lupita"))
                .andExpect(jsonPath("$[0].branch").value(nullValue()))
                .andExpect(jsonPath("$[1].name").value("Home Depot"));
    }

    @Test
    void duplicateNameAndBranchIgnoringCaseAndWhitespaceIsAConflict() throws Exception {
        create("{\"name\":\"Home Depot\",\"branch\":\"Periférico Sur\"}");
        api.post(owner, suppliers, "{\"name\":\"home  DEPOT\",\"branch\":\" periférico sur\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"))
                .andExpect(jsonPath("$.detail").value("A supplier with this name and branch already exists"));
        create("{\"name\":\"Home Depot\",\"branch\":\"Satélite\"}");
        String plain = create("{\"name\":\"Home Depot\"}");
        api.patch(owner, suppliers + "/" + plain, "{\"branch\":\"PERIFÉRICO SUR\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        assertEquals(3, count());
    }

    @Test
    void websiteMustBeHttpOrHttps() throws Exception {
        api.post(owner, suppliers, "{\"name\":\"A\",\"website\":\"ftp://a.mx\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, suppliers, "{\"name\":\"A\",\"website\":\"a.mx\"}").andExpect(status().isBadRequest());
        api.post(owner, suppliers, "{\"name\":\" \"}").andExpect(status().isBadRequest());
        assertEquals(0, count());
    }

    @Test
    void patchChangesOnlyTheFieldsSentAndEmptyStringsClearOptionals() throws Exception {
        String id = create("{\"name\":\"A\",\"branch\":\"Centro\",\"website\":\"https://a.mx\"}");
        api.patch(owner, suppliers + "/" + id, "{\"name\":\"B\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("B"))
                .andExpect(jsonPath("$.branch").value("Centro"))
                .andExpect(jsonPath("$.website").value("https://a.mx"));
        api.patch(owner, suppliers + "/" + id, "{\"branch\":\"\",\"website\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value(nullValue()))
                .andExpect(jsonPath("$.website").value(nullValue()));
        Map<String, Object> row = jdbc.queryForMap("select * from supplier where id = ?", UUID.fromString(id));
        assertEquals("B", row.get("name"));
        assertEquals(null, row.get("branch"));
        assertEquals(null, row.get("website"));
    }

    @Test
    void archivedSuppliersLeaveTheList() throws Exception {
        String id = create("{\"name\":\"Closed\"}");
        create("{\"name\":\"Open\"}");
        api.post(owner, suppliers + "/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertNotNull(jdbc.queryForObject("select archived_at from supplier where id = ?", Object.class,
                UUID.fromString(id)));
        api.get(owner, suppliers).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Open"));
        api.get(owner, suppliers + "?includeArchived=true").andExpect(jsonPath("$.length()").value(2));
        assertEquals(2, count());
    }

    private static final String FULL = """
            {"name":"Cementos del Valle","code":" prov-01 ","rfc":"cva010101ab1","address":"Av. Juárez 10",
             "neighborhood":"Centro","city":"Puebla","state":"Puebla","postalCode":"72000","phone":"222 111 2233",
             "mobile":"222 444 5566","fax":"222 777 8899","email":"ventas@cdv.mx","contact":"Ana  López",
             "notes":"Entrega martes","isDefault":true}""";

    @Test
    void createKeepsTheContactFieldsAndCreatesTheCatalog() throws Exception {
        api.get(owner, "/api/organizations/" + orgId + "/catalog").andExpect(jsonPath("$.exists").value(false));
        api.post(owner, suppliers, FULL).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PROV-01"))
                .andExpect(jsonPath("$.rfc").value("CVA010101AB1"))
                .andExpect(jsonPath("$.address").value("Av. Juárez 10"))
                .andExpect(jsonPath("$.neighborhood").value("Centro"))
                .andExpect(jsonPath("$.city").value("Puebla"))
                .andExpect(jsonPath("$.state").value("Puebla"))
                .andExpect(jsonPath("$.postalCode").value("72000"))
                .andExpect(jsonPath("$.phone").value("222 111 2233"))
                .andExpect(jsonPath("$.mobile").value("222 444 5566"))
                .andExpect(jsonPath("$.fax").value("222 777 8899"))
                .andExpect(jsonPath("$.email").value("ventas@cdv.mx"))
                .andExpect(jsonPath("$.contact").value("Ana López"))
                .andExpect(jsonPath("$.notes").value("Entrega martes"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.resourceCount").value(0));
        api.post(owner, suppliers, "{\"name\":\"Bare\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(nullValue()))
                .andExpect(jsonPath("$.isDefault").value(false));
        api.get(owner, "/api/organizations/" + orgId + "/catalog").andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.counts.suppliers").value(2));
    }

    @Test
    void codeIsUniqueAndEmailAndRfcAreChecked() throws Exception {
        create("{\"name\":\"A\",\"code\":\"P1\"}");
        api.post(owner, suppliers, "{\"name\":\"B\",\"code\":\"p1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        String b = create("{\"name\":\"B\"}");
        api.patch(owner, suppliers + "/" + b, "{\"code\":\"P1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        api.post(owner, suppliers, "{\"name\":\"C\",\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
        api.post(owner, suppliers, "{\"name\":\"C\",\"rfc\":\"ABCDEFGHIJKLMN\"}").andExpect(status().isBadRequest());
        assertEquals(2, count());
    }

    @Test
    void patchChangesAndClearsTheNewFields() throws Exception {
        String id = create(FULL);
        api.patch(owner, suppliers + "/" + id, "{\"phone\":\"\",\"contact\":\"Luis\",\"isDefault\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.contact").value("Luis"))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andExpect(jsonPath("$.email").value("ventas@cdv.mx"))
                .andExpect(jsonPath("$.code").value("PROV-01"));
        api.patch(owner, suppliers + "/" + id, "{\"code\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(nullValue()));
    }

    @Test
    void theCodeOfAnImportedSupplierCannotBePatched() throws Exception {
        String id = create("{\"name\":\"Imported\",\"code\":\"NEO-7\"}");
        jdbc.update("update supplier set source = 'NEODATA_CONSTRUBASE' where id = ?", UUID.fromString(id));
        api.patch(owner, suppliers + "/" + id, "{\"code\":\"NEO-8\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORTED_FIELD"));
        api.patch(owner, suppliers + "/" + id, "{\"code\":\"\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORTED_FIELD"));
        api.patch(owner, suppliers + "/" + id, "{\"code\":\"neo-7\",\"phone\":\"555\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("NEO-7"))
                .andExpect(jsonPath("$.phone").value("555"))
                .andExpect(jsonPath("$.source").value("NEODATA_CONSTRUBASE"));
    }

    @Test
    void queryMatchesNameCodeOrContactAndResourceCountCountsItsResources() throws Exception {
        String depot = create("{\"name\":\"Home Depot\",\"code\":\"HD-01\"}");
        create("{\"name\":\"Ferretería Lupita\",\"contact\":\"Martha Depósito\"}");
        create("{\"name\":\"Cemex\",\"code\":\"CMX\"}");
        CatalogSeed seed = new CatalogSeed(jdbc, orgId);
        UUID unit = seed.unit("PZA", "Pieza");
        seed.set(seed.resource("MAT-1", "MATERIAL", "Clavo", unit), "supplier_id", UUID.fromString(depot));
        seed.set(seed.resource("MAT-2", "MATERIAL", "Tornillo", unit), "supplier_id", UUID.fromString(depot));
        api.get(owner, suppliers + "?query=DEP").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Ferretería Lupita"))
                .andExpect(jsonPath("$[0].resourceCount").value(0))
                .andExpect(jsonPath("$[1].name").value("Home Depot"))
                .andExpect(jsonPath("$[1].resourceCount").value(2));
        api.get(owner, suppliers + "?query=cmx").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Cemex"));
        api.get(owner, suppliers + "?query=").andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void otherTenantsGetNotFound() throws Exception {
        String id = create("{\"name\":\"Mine\"}");
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        String theirs = "/api/organizations/" + otherOrg + "/suppliers";
        api.get(stranger, suppliers).andExpect(status().isNotFound());
        api.patch(stranger, theirs + "/" + id, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        api.post(stranger, theirs + "/" + id + "/archive", null).andExpect(status().isNotFound());
        api.get(stranger, theirs).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertEquals("Mine", jdbc.queryForObject("select name from supplier where id = ?", String.class,
                UUID.fromString(id)));
    }
}

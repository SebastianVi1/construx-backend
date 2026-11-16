package org.andre.construx.modules.pricing.controller;

import com.jayway.jsonpath.JsonPath;
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

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writing the Catalog by hand: creating simple Materials and Equipment rates, editing and archiving them and
 * recording costs (docs/pricing.md §2.4, §2.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceWriteFlowTest {
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
    private Members members;
    private Session owner;
    private UUID orgId;
    private String base;
    private CatalogSeed seed;
    private UUID bag;
    private UUID family;
    private UUID supplier;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        base = "/api/organizations/" + orgId;
        seed = new CatalogSeed(jdbc, orgId);
        bag = seed.unit("SACO", "Saco");
        family = seed.family("301", "AGREGADOS");
        supplier = seed.supplier("VARIOS");
    }

    private String material(String code, String cost) {
        return "{\"type\":\"MATERIAL\",\"code\":\"" + code + "\",\"name\":\"Cemento gris 50 kg\",\"unitId\":\""
                + bag + "\",\"cost\":\"" + cost + "\"}";
    }

    @Test
    void createsAMaterialWithItsFirstCostAndCreatesTheCatalog() throws Exception {
        String body = "{\"type\":\"MATERIAL\",\"code\":\"  man-cem-001 \",\"name\":\"  Cemento   gris 50 kg \","
                + "\"description\":\"Bulto de cemento\",\"unitId\":\"" + bag + "\",\"familyId\":\"" + family
                + "\",\"supplierId\":\"" + supplier + "\",\"brand\":\"Cemex\",\"cost\":\"245.5\"}";
        String id = Api.id(api.post(owner, base + "/resources", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MAN-CEM-001"))
                .andExpect(jsonPath("$.type").value("MATERIAL"))
                .andExpect(jsonPath("$.name").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.description").value("Bulto de cemento"))
                .andExpect(jsonPath("$.unit.code").value("SACO"))
                .andExpect(jsonPath("$.family.name").value("AGREGADOS"))
                .andExpect(jsonPath("$.supplier.name").value("VARIOS"))
                .andExpect(jsonPath("$.brand").value("Cemex"))
                .andExpect(jsonPath("$.isComposite").value(false))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.currentCost").value("245.50"))
                .andExpect(jsonPath("$.costUpdatedAt", notNullValue()))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].amount").value("245.50"))
                .andExpect(jsonPath("$.history[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId", notNullValue())));

        api.get(owner, base + "/resources?type=MATERIAL").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        api.get(owner, base + "/catalog").andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.counts.MATERIAL").value(1));
    }

    @Test
    void createsAnEquipmentRateButOnlyMaterialsHaveABrand() throws Exception {
        String equipment = "{\"type\":\"EQUIPMENT\",\"code\":\"eq-001\",\"name\":\"Revolvedora\",\"unitId\":\""
                + bag + "\",\"cost\":\"0\"";
        api.post(owner, base + "/resources", equipment + ",\"brand\":\"Cat\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, base + "/resources", equipment + "}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EQUIPMENT"))
                .andExpect(jsonPath("$.code").value("EQ-001"))
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.currentCost").value("0.00"));
    }

    @Test
    void refusesADuplicateCodeIgnoringCase() throws Exception {
        api.post(owner, base + "/resources", material("MAN-001", "1.00")).andExpect(status().isCreated());
        api.post(owner, base + "/resources", material(" man-001", "2.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        api.get(owner, base + "/resources").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void validatesTheCreateBody() throws Exception {
        String longCode = "X".repeat(41);
        for (String bad : new String[] {material("  ", "1.00"), material(longCode, "1.00"),
                material("A", "-1.00"), material("A", "1.005"), material("A", "1000000000000.00"),
                "{\"type\":\"MATERIAL\",\"code\":\"A\",\"name\":\"X\",\"unitId\":\"" + bag + "\"}",
                "{\"type\":\"MATERIAL\",\"code\":\"A\",\"name\":\"X\",\"cost\":\"1.00\"}",
                "{\"type\":\"MATERIAL\",\"code\":\"A\",\"name\":\" \",\"unitId\":\"" + bag + "\",\"cost\":\"1\"}"}) {
            api.post(owner, base + "/resources", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        for (String phase2 : new String[] {
                "{\"type\":\"WORK_ITEM\",\"code\":\"A\",\"name\":\"X\",\"unitId\":\"" + bag + "\",\"cost\":\"1\"}",
                "{\"type\":\"MATERIAL\",\"isComposite\":true,\"code\":\"A\",\"name\":\"X\",\"unitId\":\"" + bag
                        + "\",\"cost\":\"1\"}",
                "{\"type\":\"MATERIAL\",\"code\":\"A\",\"name\":\"X\",\"unitId\":\"" + bag
                        + "\",\"cost\":\"1\",\"laborDetail\":{\"nominalWage\":\"350.00\"}}"}) {
            api.post(owner, base + "/resources", phase2).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        api.get(owner, base + "/resources").andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void archivedUnitsFamiliesAndSuppliersCannotBeChosen() throws Exception {
        UUID oldUnit = seed.unit("OLD", "Old");
        jdbc.update("update unit set archived_at = current_timestamp where id = ?", oldUnit);
        UUID oldFamily = seed.family("999", "OLD");
        jdbc.update("update family set archived_at = current_timestamp where id = ?", oldFamily);
        UUID oldSupplier = seed.supplier("CLOSED");
        jdbc.update("update supplier set archived_at = current_timestamp where id = ?", oldSupplier);

        api.post(owner, base + "/resources", material("A", "1").replace(bag.toString(), oldUnit.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_UNIT"));
        api.post(owner, base + "/resources", withField(material("A", "1"), "familyId", oldFamily))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_FAMILY"));
        api.post(owner, base + "/resources", withField(material("A", "1"), "supplierId", oldSupplier))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_SUPPLIER"));
    }

    @Test
    void unitsFamiliesAndSuppliersOfAnotherOrganizationAreNotFound() throws Exception {
        Session other = api.signUp();
        CatalogSeed theirs = new CatalogSeed(jdbc, api.createOrganization(other, "Other " + UUID.randomUUID()));
        UUID theirUnit = theirs.unit("M3", "Metro cubico");
        UUID theirFamily = theirs.family("1", "F");
        UUID theirSupplier = theirs.supplier("S");

        api.post(owner, base + "/resources", material("A", "1").replace(bag.toString(), theirUnit.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        api.post(owner, base + "/resources", withField(material("A", "1"), "familyId", theirFamily))
                .andExpect(status().isNotFound());
        api.post(owner, base + "/resources", withField(material("A", "1"), "supplierId", theirSupplier))
                .andExpect(status().isNotFound());
        String id = create(material("A", "1"));
        api.patch(owner, base + "/resources/" + id, "{\"familyId\":\"" + theirFamily + "\"}")
                .andExpect(status().isNotFound());
        api.post(owner, base + "/resources/" + id + "/costs",
                "{\"amount\":\"2.00\",\"supplierId\":\"" + theirSupplier + "\"}").andExpect(status().isNotFound());
        api.get(owner, base + "/resources/" + id).andExpect(jsonPath("$.family").isEmpty())
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void editsDescriptiveFieldsAndAnEmptyStringClears() throws Exception {
        UUID m3 = seed.unit("M3", "Metro cubico");
        String id = create(withField(withField(material("A", "1"), "familyId", family), "supplierId", supplier)
                .replace("\"cost\"", "\"brand\":\"Cemex\",\"description\":\"Bulto\",\"cost\""));

        api.patch(owner, base + "/resources/" + id, "{\"name\":\" Cemento  blanco \",\"unitId\":\"" + m3
                        + "\",\"description\":\"\",\"brand\":\"\",\"familyId\":\"\",\"discontinued\":true,"
                        + "\"reference\":\"REF-1\",\"sapCode\":\"SAP1\",\"technicalSheet\":\"https://x.test/a.pdf\","
                        + "\"procedure\":\"Mezclar\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A"))
                .andExpect(jsonPath("$.name").value("Cemento blanco"))
                .andExpect(jsonPath("$.unit.code").value("M3"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.family").isEmpty())
                .andExpect(jsonPath("$.supplier.name").value("VARIOS"))
                .andExpect(jsonPath("$.discontinued").value(true))
                .andExpect(jsonPath("$.reference").value("REF-1"))
                .andExpect(jsonPath("$.sapCode").value("SAP1"))
                .andExpect(jsonPath("$.technicalSheet").value("https://x.test/a.pdf"))
                .andExpect(jsonPath("$.procedure").value("Mezclar"))
                .andExpect(jsonPath("$.currentCost").value("1.00"))
                .andExpect(jsonPath("$.history.length()").value(1));
        api.patch(owner, base + "/resources/" + id, "{\"supplierId\":\"\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.supplier").isEmpty())
                .andExpect(jsonPath("$.name").value("Cemento blanco"));
        api.patch(owner, base + "/resources/" + id, "{\"name\":\"\"}").andExpect(status().isBadRequest());
        api.patch(owner, base + "/resources/" + id, "{\"unitId\":\"\"}").andExpect(status().isBadRequest());
        api.patch(owner, base + "/resources/" + UUID.randomUUID(), "{\"name\":\"X\"}").andExpect(status().isNotFound());
        String equipment = create("{\"type\":\"EQUIPMENT\",\"code\":\"EQ\",\"name\":\"Grua\",\"unitId\":\"" + bag
                + "\",\"cost\":\"1\"}");
        api.patch(owner, base + "/resources/" + equipment, "{\"brand\":\"Cat\"}").andExpect(status().isBadRequest());
    }

    @Test
    void archivingHidesTheResourceButKeepsItsHistory() throws Exception {
        String id = create(material("A", "5.00"));
        create(material("B", "6.00"));

        String archivedAt = JsonPath.read(api.post(owner, base + "/resources/" + id + "/archive", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt", notNullValue()))
                .andReturn().getResponse().getContentAsString(), "$.archivedAt");
        api.post(owner, base + "/resources/" + id + "/archive", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(archivedAt));

        api.get(owner, base + "/resources").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("B"));
        api.get(owner, base + "/resources?includeArchived=true").andExpect(jsonPath("$.totalElements").value(2));
        api.get(owner, base + "/resources/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].amount").value("5.00"));
        api.post(owner, base + "/resources/" + id + "/costs", "{\"amount\":\"7.00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARCHIVED_RESOURCE"));
    }

    @Test
    void recordingACostAddsANewestHistoryEntryAndUpdatesTheCurrentCost() throws Exception {
        String id = create(material("A", "245.50"));

        api.post(owner, base + "/resources/" + id + "/costs", "{\"amount\":\"250\",\"supplierId\":\"" + supplier
                        + "\",\"sku\":\" SKU-9 \",\"note\":\"Cotizacion  marzo\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentCost").value("250.00"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].amount").value("250.00"))
                .andExpect(jsonPath("$.history[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.history[0].supplier.name").value("VARIOS"))
                .andExpect(jsonPath("$.history[0].sku").value("SKU-9"))
                .andExpect(jsonPath("$.history[0].note").value("Cotizacion marzo"))
                .andExpect(jsonPath("$.history[0].recordedBy.membershipId", notNullValue()))
                .andExpect(jsonPath("$.history[1].amount").value("245.50"));
        api.post(owner, base + "/resources/" + id + "/costs", "{\"amount\":\"250.00\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].supplier").isEmpty());
        api.get(owner, base + "/resources/" + id + "/costs").andExpect(jsonPath("$.totalElements").value(3));

        for (String bad : new String[] {"{}", "{\"amount\":\"-1\"}", "{\"amount\":\"1.234\"}",
                "{\"amount\":\"1\",\"sku\":\"" + "S".repeat(65) + "\"}",
                "{\"amount\":\"1\",\"note\":\"" + "N".repeat(161) + "\"}"}) {
            api.post(owner, base + "/resources/" + id + "/costs", bad).andExpect(status().isBadRequest());
        }
        api.get(owner, base + "/resources/" + id + "/costs").andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void compositeAndLaborCostsAreNotRecordedByHand() throws Exception {
        seed.catalog();
        UUID gang = seed.resource("CUAD-01", "LABOR", "Cuadrilla", bag);
        seed.set(gang, "is_composite", true);
        UUID labor = seed.resource("MO-01", "LABOR", "Peon", bag);

        api.post(owner, base + "/resources/" + gang + "/costs", "{\"amount\":\"1.00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DERIVED_COST"));
        api.post(owner, base + "/resources/" + labor + "/costs", "{\"amount\":\"1.00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LABOR_COST_FROM_WAGE"));
    }

    @Test
    void historyEntriesCannotBeChangedOrDeleted() throws Exception {
        String id = create(material("A", "1.00"));
        String costs = base + "/resources/" + id + "/costs";
        String entry = costs + "/" + JsonPath.read(api.get(owner, base + "/resources/" + id)
                .andReturn().getResponse().getContentAsString(), "$.history[0].id");
        api.put(owner, costs, "{\"amount\":\"2\"}").andExpect(status().isMethodNotAllowed());
        api.patch(owner, costs, "{\"amount\":\"2\"}").andExpect(status().isMethodNotAllowed());
        api.delete(owner, costs).andExpect(status().isMethodNotAllowed());
        api.put(owner, entry, "{\"amount\":\"2\"}").andExpect(status().isMethodNotAllowed());
        api.patch(owner, entry, "{\"amount\":\"2\"}").andExpect(status().isMethodNotAllowed());
        api.delete(owner, entry).andExpect(status().isMethodNotAllowed());
        api.get(owner, base + "/resources/" + id).andExpect(jsonPath("$.currentCost").value("1.00"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void financeWritesButMemberAndWorkerCannot() throws Exception {
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        String id = create(material("A", "1.00"));
        api.post(finance, base + "/resources/" + id + "/costs", "{\"amount\":\"2.00\"}").andExpect(status().isCreated());
        api.post(finance, base + "/resources", material("B", "1.00")).andExpect(status().isCreated());

        for (String role : new String[] {"MEMBER", "WORKER"}) {
            Session s = api.signUp();
            members.add(orgId, s.user(), role);
            api.post(s, base + "/resources", material("C", "1.00")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            api.patch(s, base + "/resources/" + id, "{\"name\":\"X\"}").andExpect(status().isForbidden());
            api.post(s, base + "/resources/" + id + "/archive", null).andExpect(status().isForbidden());
            api.post(s, base + "/resources/" + id + "/costs", "{\"amount\":\"3.00\"}").andExpect(status().isForbidden());
        }
        Session stranger = api.signUp();
        api.post(stranger, base + "/resources", material("D", "1.00")).andExpect(status().isNotFound());
        api.get(owner, base + "/resources/" + id).andExpect(jsonPath("$.name").value("Cemento gris 50 kg"))
                .andExpect(jsonPath("$.currentCost").value("2.00"))
                .andExpect(jsonPath("$.archivedAt").isEmpty());
        api.get(owner, base + "/resources").andExpect(jsonPath("$.totalElements").value(2));
    }

    private String create(String body) throws Exception {
        return Api.id(api.post(owner, base + "/resources", body).andExpect(status().isCreated()));
    }

    private static String withField(String json, String field, UUID value) {
        return json.substring(0, json.length() - 1) + ",\"" + field + "\":\"" + value + "\"}";
    }
}

package org.andre.construx.modules.floorplan;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation contract of every floor plan endpoint: non-members and other organizations' Sites get 404,
 * members lacking VIEW_PLAN / MANAGE_PLAN (or Site access for PROJECT scope) get 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FloorPlanEndpointAccessTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;

    private enum Need { VIEW, MANAGE }

    private record Call(Need need, String method, String path, String body) {}

    private static final String FP = "/floor-plans/" + UUID.randomUUID();
    private static final String ZONE = FP + "/zones/" + UUID.randomUUID();
    private static final String ASSIGNMENT = ZONE + "/assignments/" + UUID.randomUUID();

    private static final List<Call> CALLS = List.of(
            new Call(Need.VIEW, "GET", "/floor-plans", null),
            new Call(Need.VIEW, "GET", FP, null),
            new Call(Need.VIEW, "GET", FP + "/state", null),
            new Call(Need.VIEW, "GET", FP + "/calibration", null),
            new Call(Need.VIEW, "GET", FP + "/zones", null),
            new Call(Need.VIEW, "GET", ZONE + "/audit", null),
            new Call(Need.VIEW, "GET", ZONE + "/assignments", null),
            new Call(Need.VIEW, "GET", FP + "/assignments", null),
            new Call(Need.VIEW, "GET", FP + "/zones/assignment-counts", null),
            new Call(Need.MANAGE, "GET", "/floor-plans/roster", null),
            new Call(Need.MANAGE, "POST", "/floor-plans/uploads",
                    "{\"name\":\"L1\",\"pageNumber\":1,\"contentType\":\"application/pdf\",\"sizeBytes\":1000}"),
            new Call(Need.MANAGE, "POST", FP + "/complete", null),
            new Call(Need.MANAGE, "DELETE", FP, null),
            new Call(Need.MANAGE, "PUT", FP + "/calibration",
                    "{\"pointAX\":0,\"pointAY\":0,\"pointBX\":10,\"pointBY\":0,\"lengthMeters\":5}"),
            new Call(Need.MANAGE, "POST", FP + "/zones", "{\"name\":\"Z\",\"points\":[[0,0],[1,0],[1,1]]}"),
            new Call(Need.MANAGE, "PATCH", ZONE, "{\"name\":\"Z\",\"points\":[[0,0],[1,0],[1,1]],\"version\":0}"),
            new Call(Need.MANAGE, "DELETE", ZONE, null),
            new Call(Need.MANAGE, "POST", ZONE + "/assignments", "{}"),
            new Call(Need.MANAGE, "POST", ASSIGNMENT + "/close", null),
            new Call(Need.MANAGE, "POST", ASSIGNMENT + "/reassign", "{\"newZoneId\":\"" + UUID.randomUUID() + "\"}"));

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private UUID siteId;
    private String site;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        siteId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        site = "/api/organizations/" + orgId + "/projects/" + siteId;
    }

    @Test
    void nonMembersGetNotFoundOnEveryEndpoint() throws Exception {
        Session stranger = api.signUp();
        for (Call call : CALLS) {
            assertEquals(404, statusOf(stranger, site, call), call.toString());
        }
    }

    @Test
    void anotherOrganizationsSiteIsNotFoundOnEveryEndpoint() throws Exception {
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        String foreignSite = "/api/organizations/" + otherOrg + "/projects/" + siteId;
        for (Call call : CALLS) {
            assertEquals(404, statusOf(other, foreignSite, call), call.toString());
        }
    }

    @Test
    void membersWithoutPlanPermissionsAreForbiddenOnEveryEndpoint() throws Exception {
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        for (Call call : CALLS) {
            assertEquals(403, statusOf(finance, site, call), call.toString());
        }
    }

    @Test
    void siteScopedViewersNeedSiteAccess() throws Exception {
        Session worker = api.signUp();
        members.add(orgId, worker.user(), "WORKER");
        for (Call call : CALLS) {
            assertEquals(403, statusOf(worker, site, call), call.toString());
        }
    }

    @Test
    void assignedWorkerCanViewButNotManage() throws Exception {
        Session worker = api.signUp();
        members.grantSite(siteId, members.add(orgId, worker.user(), "WORKER"));
        for (Call call : CALLS) {
            int expected = call.need() == Need.MANAGE ? 403 : call.path().equals("/floor-plans") ? 200 : 404;
            assertEquals(expected, statusOf(worker, site, call), call.toString());
        }
    }

    @Test
    void ownerRequestsAnUploadAndSeesItListed() throws Exception {
        String created = api.post(owner, site + "/floor-plans/uploads",
                        "{\"name\":\"L1\",\"pageNumber\":1,\"contentType\":\"application/pdf\",\"sizeBytes\":1000}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String floorPlanId = JsonPath.read(created, "$.floorPlanId");
        api.get(owner, site + "/floor-plans").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(floorPlanId))
                .andExpect(jsonPath("$[0].projectId").value(siteId.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    private int statusOf(Session session, String base, Call call) throws Exception {
        String path = base + call.path();
        return (switch (call.method()) {
            case "GET" -> api.get(session, path);
            case "POST" -> api.post(session, path, call.body());
            case "PUT" -> api.put(session, path, call.body());
            case "PATCH" -> api.patch(session, path, call.body());
            case "DELETE" -> api.delete(session, path);
            default -> throw new IllegalArgumentException(call.method());
        }).andReturn().getResponse().getStatus();
    }
}

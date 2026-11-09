package org.andre.construx.modules.crew;

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
 * Tenant isolation of the crew and equipment endpoints: non-members get 404, members without VIEW_PLAN /
 * MANAGE_PLAN at organization scope get 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrewEndpointAccessTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;

    private record Call(String method, String path, String body) {}

    private static final String CREW = "/crews/" + UUID.randomUUID();
    private static final String EQUIPMENT = "/equipment/" + UUID.randomUUID();

    private static final List<Call> CALLS = List.of(
            new Call("GET", "/crews", null),
            new Call("POST", "/crews", "{\"name\":\"C\",\"leaderMembershipId\":\"" + UUID.randomUUID() + "\"}"),
            new Call("POST", CREW + "/members", "{\"membershipId\":\"" + UUID.randomUUID() + "\"}"),
            new Call("DELETE", CREW + "/members/" + UUID.randomUUID(), null),
            new Call("DELETE", CREW, null),
            new Call("GET", "/equipment", null),
            new Call("POST", "/equipment", "{\"type\":\"Crane\",\"identifier\":\"CR-1\"}"),
            new Call("PATCH", EQUIPMENT, "{\"type\":\"Crane\"}"),
            new Call("DELETE", EQUIPMENT, null));

    private Api api;
    private Members members;
    private Session owner;
    private UUID orgId;
    private String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
    }

    @Test
    void nonMembersGetNotFoundOnEveryEndpoint() throws Exception {
        Session stranger = api.signUp();
        for (Call call : CALLS) {
            assertEquals(404, statusOf(stranger, call), call.toString());
        }
    }

    @Test
    void membersWithoutOrganizationWidePlanPermissionsAreForbidden() throws Exception {
        for (String role : List.of("FINANCE", "WORKER")) {
            Session member = api.signUp();
            members.add(orgId, member.user(), role);
            for (Call call : CALLS) {
                assertEquals(403, statusOf(member, call), role + " " + call);
            }
        }
    }

    @Test
    void ownerCreatesACrewLedByThemselvesAndSeesItListed() throws Exception {
        UUID leader = members.ownerMembership(orgId, owner.user());
        api.post(owner, org + "/crews", "{\"name\":\"Concrete\",\"leaderMembershipId\":\"" + leader + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizationId").value(orgId.toString()));
        api.get(owner, org + "/crews").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Concrete"))
                .andExpect(jsonPath("$[0].leaderMembershipId").value(leader.toString()));
    }

    @Test
    void ownerCannotLeadACrewWithAnotherOrganizationsMember() throws Exception {
        Session other = api.signUp();
        UUID otherOrg = api.createOrganization(other, "Other " + UUID.randomUUID());
        UUID foreign = members.ownerMembership(otherOrg, other.user());
        api.post(owner, org + "/crews", "{\"name\":\"C\",\"leaderMembershipId\":\"" + foreign + "\"}")
                .andExpect(status().isNotFound());
    }

    private int statusOf(Session session, Call call) throws Exception {
        String path = org + call.path();
        return (switch (call.method()) {
            case "GET" -> api.get(session, path);
            case "POST" -> api.post(session, path, call.body());
            case "PATCH" -> api.patch(session, path, call.body());
            case "DELETE" -> api.delete(session, path);
            default -> throw new IllegalArgumentException(call.method());
        }).andReturn().getResponse().getStatus();
    }
}

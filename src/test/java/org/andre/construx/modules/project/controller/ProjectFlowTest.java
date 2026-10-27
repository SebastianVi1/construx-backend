package org.andre.construx.modules.project.controller;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectFlowTest {
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
        base = "/api/organizations/" + orgId + "/projects";
    }

    @Test
    void ownerCreatesASiteWithDetailsAndPlannedIsTheDefault() throws Exception {
        api.post(owner, base, "{\"name\":\" Torre Norte \",\"address\":\"Av. Siempre Viva 742\","
                        + "\"startDate\":\"2026-11-01\",\"expectedEndDate\":\"2027-05-30\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Torre Norte"))
                .andExpect(jsonPath("$.address").value("Av. Siempre Viva 742"))
                .andExpect(jsonPath("$.startDate").value("2026-11-01"))
                .andExpect(jsonPath("$.expectedEndDate").value("2027-05-30"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.activityCount").value(0))
                .andExpect(jsonPath("$.doneCount").value(0));
        api.post(owner, base, "{\"name\":\"Active one\",\"status\":\"ACTIVE\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        api.post(owner, base, "{\"name\":\" \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, base, "{\"name\":\"X\",\"startDate\":\"2027-01-02\",\"expectedEndDate\":\"2027-01-01\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, base, "{\"name\":\"X\",\"status\":\"DEMOLISHED\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }

    @Test
    void listHidesArchivedByDefaultAndFiltersByStatus() throws Exception {
        api.post(owner, base, "{\"name\":\"Live\"}").andExpect(status().isCreated());
        api.post(owner, base, "{\"name\":\"Old\",\"status\":\"ARCHIVED\"}").andExpect(status().isCreated());
        api.get(owner, base).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Live"));
        api.get(owner, base + "?status=ARCHIVED")
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Old"));
    }

    @Test
    void patchChangesOnlyTheSentFieldsAndRechecksDates() throws Exception {
        String id = Api.id(api.post(owner, base, "{\"name\":\"Tower\",\"address\":\"Main 1\","
                + "\"startDate\":\"2026-11-01\"}"));
        api.patch(owner, base + "/" + id, "{\"name\":\"Tower B\",\"status\":\"ON_HOLD\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tower B"))
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.address").value("Main 1"))
                .andExpect(jsonPath("$.startDate").value("2026-11-01"));
        api.patch(owner, base + "/" + id, "{\"expectedEndDate\":\"2026-10-01\"}")
                .andExpect(status().isBadRequest());
        api.patch(owner, base + "/" + id, "{\"address\":\"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").isEmpty());
        api.get(owner, base + "/" + id).andExpect(jsonPath("$.name").value("Tower B"));
    }

    @Test
    void patchClearsDatesOnlyWhenAskedExplicitly() throws Exception {
        String id = Api.id(api.post(owner, base, "{\"name\":\"Dated\",\"startDate\":\"2026-11-01\","
                + "\"expectedEndDate\":\"2027-05-30\"}"));
        api.patch(owner, base + "/" + id, "{\"startDate\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-11-01"));
        api.patch(owner, base + "/" + id, "{\"clear\":[\"startDate\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").isEmpty())
                .andExpect(jsonPath("$.expectedEndDate").value("2027-05-30"));
        api.patch(owner, base + "/" + id, "{\"clear\":[\"expectedEndDate\"],\"name\":\"Undated\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedEndDate").isEmpty())
                .andExpect(jsonPath("$.name").value("Undated"));
        api.patch(owner, base + "/" + id, "{\"clear\":[\"name\"]}").andExpect(status().isBadRequest());
        api.patch(owner, base + "/" + id, "{\"clear\":[\"startDate\"],\"startDate\":\"2026-01-01\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void workerSeesOnlyAssignedSitesAndCannotManage() throws Exception {
        String assigned = Api.id(api.post(owner, base, "{\"name\":\"Assigned\"}"));
        String other = Api.id(api.post(owner, base, "{\"name\":\"Other\"}"));
        Session worker = api.signUp();
        UUID membership = members.add(orgId, worker.user(), "WORKER");
        members.grantSite(UUID.fromString(assigned), membership);

        api.get(worker, base).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(assigned))
                .andExpect(jsonPath("$[0].activityCount").value(0));
        api.get(worker, base + "/" + assigned).andExpect(status().isOk());
        api.get(worker, base + "/" + other).andExpect(status().isForbidden());
        api.post(worker, base, "{\"name\":\"Mine\"}").andExpect(status().isForbidden());
        api.patch(worker, base + "/" + assigned, "{\"name\":\"Renamed\"}").andExpect(status().isForbidden());
    }

    @Test
    void roleMatrixForCreateAndCounts() throws Exception {
        Session admin = api.signUp();
        members.add(orgId, admin.user(), "ADMINISTRATOR");
        Session member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");

        api.post(admin, base, "{\"name\":\"By admin\"}").andExpect(status().isCreated());
        api.post(member, base, "{\"name\":\"By member\"}").andExpect(status().isForbidden());
        api.post(finance, base, "{\"name\":\"By finance\"}").andExpect(status().isForbidden());
        api.get(member, base).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityCount").value(0));
        // Finance sees sites but not task progress
        api.get(finance, base).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityCount").isEmpty())
                .andExpect(jsonPath("$[0].doneCount").isEmpty());
    }

    @Test
    void countsReflectTheSitesTasks() throws Exception {
        String id = Api.id(api.post(owner, base, "{\"name\":\"Counted\"}"));
        UUID projectId = UUID.fromString(id);
        UUID access = members.grantSite(projectId, members.ownerMembership(orgId, owner.user()));
        UUID goal = UUID.randomUUID();
        Timestamp due = Timestamp.from(Instant.now().plusSeconds(86_400));
        jdbc.update("insert into goal (id, project_id, name, start_at, due_at) values (?, ?, 'Foundations', ?, ?)", goal, projectId, due, due);
        jdbc.update("insert into activity (id, goal_id, name, assignee_access_id, start_at, due_at, status) values (?, ?, 'Dig', ?, ?, ?, 'DONE')",
                UUID.randomUUID(), goal, access, due, due);
        jdbc.update("insert into activity (id, goal_id, name, assignee_access_id, start_at, due_at, status) values (?, ?, 'Pour', ?, ?, ?, 'TODO')",
                UUID.randomUUID(), goal, access, due, due);
        api.get(owner, base + "/" + id).andExpect(jsonPath("$.activityCount").value(2))
                .andExpect(jsonPath("$.doneCount").value(1));
    }

    @Test
    void otherTenantsAndUnknownIdsGetNotFound() throws Exception {
        String id = Api.id(api.post(owner, base, "{\"name\":\"Private\"}"));
        Session stranger = api.signUp();
        UUID strangerOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, base).andExpect(status().isNotFound());
        api.get(stranger, base + "/" + id).andExpect(status().isNotFound());
        api.post(stranger, base, "{\"name\":\"Intrude\"}").andExpect(status().isNotFound());
        // the stranger's own org path with our project id
        api.get(stranger, "/api/organizations/" + strangerOrg + "/projects/" + id).andExpect(status().isNotFound());
        api.patch(stranger, "/api/organizations/" + strangerOrg + "/projects/" + id, "{\"name\":\"x\"}")
                .andExpect(status().isNotFound());
        api.get(owner, base + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}

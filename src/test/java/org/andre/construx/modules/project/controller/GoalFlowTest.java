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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoalFlowTest {
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
    private UUID projectId;
    private String goals;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        projectId = UUID.fromString(Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects",
                "{\"name\":\"Tower\"}")));
        goals = "/api/organizations/" + orgId + "/projects/" + projectId + "/goals";
    }

    @Test
    void ownerCreatesPhasesAndTheListIsOrderedByDueDate() throws Exception {
        api.post(owner, goals, "{\"name\":\" Structure \",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.name").value("Structure"))
                .andExpect(jsonPath("$.startAt").value("2027-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.activityCount").value(0))
                .andExpect(jsonPath("$.doneCount").value(0))
                .andExpect(jsonPath("$.completed").value(false));
        api.post(owner, goals, "{\"name\":\"Foundations\",\"startAt\":\"2026-12-01T00:00:00Z\",\"dueAt\":\"2026-12-15T23:59:00Z\"}")
                .andExpect(status().isCreated());
        api.get(owner, goals).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Foundations"))
                .andExpect(jsonPath("$[1].name").value("Structure"));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        api.post(owner, goals, "{\"name\":\"No date\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, goals, "{\"name\":\" \",\"dueAt\":\"2027-01-01T00:00:00Z\"}").andExpect(status().isBadRequest());
    }

    @Test
    void patchRenamesAndCannotMoveTheDeadlineBeforeItsTasks() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        task(UUID.fromString(id), "2027-02-01T00:00:00Z", "TODO");
        api.patch(owner, goals + "/" + id, "{\"name\":\"Phase 1\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phase 1"))
                .andExpect(jsonPath("$.dueAt").value("2027-03-01T00:00:00Z"));
        api.patch(owner, goals + "/" + id, "{\"dueAt\":\"2027-01-01T00:00:00Z\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.patch(owner, goals + "/" + id, "{\"dueAt\":\"2027-02-01T00:00:00Z\"}").andExpect(status().isOk());
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.dueAt").value("2027-02-01T00:00:00Z"));
    }

    @Test
    void countsAndCompletedFollowTheTasks() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        UUID goal = UUID.fromString(id);
        task(goal, "2027-02-01T00:00:00Z", "DONE");
        UUID open = task(goal, "2027-02-02T00:00:00Z", "IN_PROGRESS");
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.activityCount").value(2))
                .andExpect(jsonPath("$.doneCount").value(1)).andExpect(jsonPath("$.completed").value(false));
        jdbc.update("update activity set status = 'DONE' where id = ?", open);
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void deleteOnlyWhenEmpty() throws Exception {
        String empty = Api.id(api.post(owner, goals, "{\"name\":\"Empty\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        String busy = Api.id(api.post(owner, goals, "{\"name\":\"Busy\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        task(UUID.fromString(busy), "2027-02-01T00:00:00Z", "TODO");
        api.delete(owner, goals + "/" + busy).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        api.delete(owner, goals + "/" + empty).andExpect(status().isNoContent());
        api.get(owner, goals + "/" + empty).andExpect(status().isNotFound());
    }

    @Test
    void membersManageGoalsWorkersOnlyViewTheirSitesFinanceCannotSeeThem() throws Exception {
        Session member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        Session worker = api.signUp();
        UUID workerMembership = members.add(orgId, worker.user(), "WORKER");
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");

        String id = Api.id(api.post(member, goals, "{\"name\":\"By member\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")
                .andExpect(status().isCreated()));
        api.patch(member, goals + "/" + id, "{\"name\":\"Edited by member\"}").andExpect(status().isOk());

        api.get(worker, goals).andExpect(status().isForbidden());
        members.grantSite(projectId, workerMembership);
        api.get(worker, goals).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityCount").value(0));
        api.post(worker, goals, "{\"name\":\"By worker\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")
                .andExpect(status().isForbidden());
        api.delete(worker, goals + "/" + id).andExpect(status().isForbidden());
        api.get(finance, goals).andExpect(status().isForbidden());
    }

    @Test
    void otherTenantsAndOtherSitesGetNotFound() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        String otherSite = Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"Other\"}"));
        api.get(owner, "/api/organizations/" + orgId + "/projects/" + otherSite + "/goals/" + id)
                .andExpect(status().isNotFound());
        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.get(stranger, goals).andExpect(status().isNotFound());
        api.get(stranger, goals + "/" + id).andExpect(status().isNotFound());
        api.delete(stranger, goals + "/" + id).andExpect(status().isNotFound());
        api.get(owner, goals + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void createRequiresAStartThatIsNotAfterTheDue() throws Exception {
        api.post(owner, goals, "{\"name\":\"No start\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(owner, goals, "{\"name\":\"Backwards\",\"startAt\":\"2027-03-02T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, goals).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void patchMovesTheStartButNotPastItsTasks() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        api.patch(owner, goals + "/" + id, "{\"startAt\":\"2027-01-10T00:00:00Z\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2027-01-10T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-03-01T00:00:00Z"));
        task(UUID.fromString(id), "2027-02-01T00:00:00Z", "TODO");
        api.patch(owner, goals + "/" + id, "{\"startAt\":\"2027-02-02T00:00:00Z\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.startAt").value("2027-01-10T00:00:00Z"));
    }

    @Test
    void patchAcceptsBothDatesAsAPair() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-01-05T00:00:00Z\"}"));
        api.patch(owner, goals + "/" + id, "{\"startAt\":\"2027-02-01T00:00:00Z\",\"dueAt\":\"2027-02-05T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2027-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-02-05T00:00:00Z"));
    }

    @Test
    void shiftMovesThePhaseAndAllItsTasks() throws Exception {
        Session member = api.signUp();
        members.add(orgId, member.user(), "MEMBER");
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        UUID task = task(UUID.fromString(id), "2027-02-01T00:00:00Z", "TODO");
        api.post(member, goals + "/" + id + "/shift", "{\"days\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.startAt").value("2027-01-04T00:00:00Z"))
                .andExpect(jsonPath("$.dueAt").value("2027-03-04T00:00:00Z"))
                .andExpect(jsonPath("$.activityCount").value(1));
        assertEquals(Instant.parse("2027-02-04T00:00:00Z"), jdbc.queryForObject(
                "select start_at from activity where id = ?", Timestamp.class, task).toInstant());
        assertEquals(Instant.parse("2027-02-04T00:00:00Z"), jdbc.queryForObject(
                "select due_at from activity where id = ?", Timestamp.class, task).toInstant());
        api.post(owner, goals + "/" + id + "/shift", "{\"days\":-4}").andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2026-12-31T00:00:00Z"));
    }

    @Test
    void shiftRejectsZeroAndMissingDays() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        api.post(owner, goals + "/" + id + "/shift", "{\"days\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        api.post(owner, goals + "/" + id + "/shift", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.startAt").value("2027-01-01T00:00:00Z"));
    }

    @Test
    void shiftNeedsManageGoalAndStaysInsideTheTenant() throws Exception {
        String id = Api.id(api.post(owner, goals, "{\"name\":\"Phase\",\"startAt\":\"2027-01-01T00:00:00Z\",\"dueAt\":\"2027-03-01T00:00:00Z\"}"));
        Session worker = api.signUp();
        members.grantSite(projectId, members.add(orgId, worker.user(), "WORKER"));
        api.post(worker, goals + "/" + id + "/shift", "{\"days\":1}").andExpect(status().isForbidden());
        String otherSite = Api.id(api.post(owner, "/api/organizations/" + orgId + "/projects", "{\"name\":\"Other\"}"));
        api.post(owner, "/api/organizations/" + orgId + "/projects/" + otherSite + "/goals/" + id + "/shift", "{\"days\":1}")
                .andExpect(status().isNotFound());
        Session stranger = api.signUp();
        api.createOrganization(stranger, "Other " + UUID.randomUUID());
        api.post(stranger, goals + "/" + id + "/shift", "{\"days\":1}").andExpect(status().isNotFound());
        api.get(owner, goals + "/" + id).andExpect(jsonPath("$.startAt").value("2027-01-01T00:00:00Z"));
    }

    private UUID task(UUID goal, String dueAt, String status) {
        UUID access = accesses.findProjectIdsByMembership(members.ownerMembership(orgId, owner.user())).contains(projectId)
                ? jdbc.queryForObject("select id from project_access where project_id = ? and organization_membership_id = ?",
                        UUID.class, projectId, members.ownerMembership(orgId, owner.user()))
                : members.grantSite(projectId, members.ownerMembership(orgId, owner.user()));
        UUID id = UUID.randomUUID();
        Timestamp due = Timestamp.from(Instant.parse(dueAt));
        jdbc.update("insert into activity (id, goal_id, name, assignee_access_id, start_at, due_at, status) values (?, ?, 'T', ?, ?, ?, ?)",
                id, goal, access, due, due, status);
        return id;
    }
}

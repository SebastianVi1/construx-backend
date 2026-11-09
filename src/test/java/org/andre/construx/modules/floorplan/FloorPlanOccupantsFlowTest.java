package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Floor plans name what stands in each zone themselves: anyone who can view the plan sees names and photos
 * without the members list, and planners drag from a roster of the Site.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FloorPlanOccupantsFlowTest {
    @TestConfiguration
    static class Storage {
        @Bean @Primary ObjectStorage inMemoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired FloorPlanRepository floorPlans;
    @Autowired FloorPlanZoneRepository zones;
    @Autowired PlatformTransactionManager txm;

    private Api api;
    private Members members;
    private Session owner, worker;
    private UUID orgId, siteId, zoneId, workerMembership, workerAccess;
    private String org, site, plan;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        siteId = UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}")));
        site = org + "/projects/" + siteId;

        worker = api.signUp();
        UserProfile profile = new UserProfile(worker.user(), "Ana Perez");
        profile.changeAvatarKey("avatars/ana.png");
        profiles.save(profile);
        workerMembership = members.add(orgId, worker.user(), "WORKER");
        workerAccess = members.grantSite(siteId, workerMembership);

        UUID ownerMembership = members.ownerMembership(orgId, owner.user());
        UUID floorPlanId = new TransactionTemplate(txm).execute(s -> {
            FloorPlan fp = new FloorPlan(projects.findById(siteId).orElseThrow(),
                    memberships.findById(ownerMembership).orElseThrow(), "L1", null, null, 1, "image/png", 1000);
            fp.startProcessing();
            fp.markReady("rendered.png", Instant.now());
            floorPlans.save(fp);
            FloorPlanZone zone = zones.save(new FloorPlanZone(fp, memberships.findById(ownerMembership).orElseThrow(),
                    "Slab A", null, ZoneStatus.IN_PROGRESS, List.of(new double[]{0, 0}, new double[]{4, 0},
                    new double[]{4, 4}, new double[]{0, 4})));
            zoneId = zone.getId();
            return fp.getId();
        });
        plan = site + "/floor-plans/" + floorPlanId;
    }

    @Test
    void workerSeesTheNameAndPhotoOfAPersonOnThePlan() throws Exception {
        assign("{\"membershipId\":\"" + workerMembership + "\"}");

        api.get(worker, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zone.id").value(zoneId.toString()))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.kind").value("PERSON"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.id").value(workerMembership.toString()))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.label").value("Ana Perez"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.avatarUrl")
                        .value("memory://download/avatars/ana.png"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].membershipId")
                        .value(workerMembership.toString()))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].fullName").value("Ana Perez"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].roleName").value("Trabajador"));
    }

    @Test
    void aCrewOnThePlanListsItsMembersWithNamesAndPhotos() throws Exception {
        String crewId = Api.id(api.post(owner, org + "/crews",
                "{\"name\":\"Rebar crew\",\"leaderMembershipId\":\"" + workerMembership + "\"}"));
        assign("{\"crewId\":\"" + crewId + "\"}");

        api.get(worker, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.kind").value("CREW"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.id").value(crewId))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.label").value("Rebar crew"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members", hasSize(1)))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].fullName").value("Ana Perez"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].avatarUrl")
                        .value("memory://download/avatars/ana.png"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members[0].crewLeader").value(true));
    }

    @Test
    void equipmentOnThePlanIsLabelledByTypeAndIdentifier() throws Exception {
        String equipmentId = Api.id(api.post(owner, org + "/equipment",
                "{\"type\":\"Excavator\",\"identifier\":\"EX-01\"}"));
        String created = assign("{\"equipmentId\":\"" + equipmentId + "\"}");

        assertTrue(created.contains("\"kind\":\"EQUIPMENT\""), created);
        api.get(worker, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.kind").value("EQUIPMENT"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.id").value(equipmentId))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.label").value("Excavator — EX-01"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.equipment.type").value("Excavator"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.equipment.identifier").value("EX-01"))
                .andExpect(jsonPath("$[0].activeAssignments[0].occupant.members", hasSize(0)));
    }

    @Test
    void aPersonOnThePlanCarriesTheirUnfinishedTasksOnThisSite() throws Exception {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String goalId = Api.id(api.post(owner, site + "/goals", "{\"name\":\"Structure\",\"startAt\":\"" + start
                + "\",\"dueAt\":\"" + start.plus(30, ChronoUnit.DAYS) + "\"}"));
        String tasks = site + "/goals/" + goalId + "/activities";
        String team = "\"assignees\":[{\"accessId\":\"" + workerAccess + "\",\"taskRole\":\"LEAD\"}]";
        String open = Api.id(api.post(owner, tasks, "{\"name\":\"Pour slab\",\"startAt\":\"" + start
                + "\",\"dueAt\":\"" + start.plus(5, ChronoUnit.DAYS) + "\"," + team + "}"));
        String done = Api.id(api.post(owner, tasks, "{\"name\":\"Formwork\",\"startAt\":\"" + start
                + "\",\"dueAt\":\"" + start.plus(2, ChronoUnit.DAYS) + "\"," + team + "}"));
        api.post(owner, tasks + "/" + done + "/start", null).andExpect(status().isOk());
        api.post(owner, tasks + "/" + done + "/complete", null).andExpect(status().isOk());
        assign("{\"membershipId\":\"" + workerMembership + "\"}");

        String member = "$[0].activeAssignments[0].occupant.members[0]";
        api.get(worker, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath(member + ".openTasks", hasSize(1)))
                .andExpect(jsonPath(member + ".openTasks[0].id").value(open))
                .andExpect(jsonPath(member + ".openTasks[0].goalId").value(goalId))
                .andExpect(jsonPath(member + ".openTasks[0].projectId").value(siteId.toString()))
                .andExpect(jsonPath(member + ".openTasks[0].name").value("Pour slab"))
                .andExpect(jsonPath(member + ".openTasks[0].goalName").value("Structure"))
                .andExpect(jsonPath(member + ".openTasks[0].status").value("TODO"))
                .andExpect(jsonPath(member + ".openTasks[0].taskRole").value("LEAD"));
    }

    @Test
    void siteManagerWithoutMembersPermissionGetsTheSiteRoster() throws Exception {
        Session manager = api.signUp();
        profiles.save(new UserProfile(manager.user(), "Carmen Ruiz"));
        UUID managerMembership = members.add(orgId, manager.user(), "SITE_MANAGER");
        members.grantSite(siteId, managerMembership);
        Session outsider = api.signUp();
        members.add(orgId, outsider.user(), "WORKER"); // in the company, not on this Site
        String crewId = Api.id(api.post(owner, org + "/crews",
                "{\"name\":\"Rebar crew\",\"leaderMembershipId\":\"" + workerMembership + "\"}"));
        String equipmentId = Api.id(api.post(owner, org + "/equipment",
                "{\"type\":\"Excavator\",\"identifier\":\"EX-01\"}"));
        String retiredId = Api.id(api.post(owner, org + "/equipment", "{\"type\":\"Crane\",\"identifier\":\"CR-9\"}"));
        api.patch(owner, org + "/equipment/" + retiredId, "{\"active\":false}").andExpect(status().isOk());

        api.get(manager, org + "/members").andExpect(status().isForbidden());
        api.get(manager, site + "/floor-plans/roster").andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].membershipId").value(containsInAnyOrder(
                        workerMembership.toString(), managerMembership.toString())))
                .andExpect(jsonPath("$.people[?(@.membershipId == '" + workerMembership + "')].fullName")
                        .value(containsInAnyOrder("Ana Perez")))
                .andExpect(jsonPath("$.people[?(@.membershipId == '" + workerMembership + "')].avatarUrl")
                        .value(containsInAnyOrder("memory://download/avatars/ana.png")))
                .andExpect(jsonPath("$.people[?(@.membershipId == '" + workerMembership + "')].roleName")
                        .value(containsInAnyOrder("Trabajador")))
                .andExpect(jsonPath("$.crews[0].id").value(crewId))
                .andExpect(jsonPath("$.crews[0].name").value("Rebar crew"))
                .andExpect(jsonPath("$.crews[0].memberCount").value(1))
                .andExpect(jsonPath("$.equipment", hasSize(1)))
                .andExpect(jsonPath("$.equipment[0].id").value(equipmentId))
                .andExpect(jsonPath("$.equipment[0].type").value("Excavator"))
                .andExpect(jsonPath("$.equipment[0].identifier").value("EX-01"));

        // The roster is what a planner drags from: a drop works for the manager too
        api.post(manager, plan + "/zones/" + zoneId + "/assignments", "{\"crewId\":\"" + crewId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignment.occupant.label").value("Rebar crew"));
    }

    private String assign(String body) throws Exception {
        return api.post(owner, plan + "/zones/" + zoneId + "/assignments", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}

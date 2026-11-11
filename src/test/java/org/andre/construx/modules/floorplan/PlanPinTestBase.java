package org.andre.construx.modules.floorplan;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A Site with a READY floor plan, one zone "Slab A" over (0,0)-(4,4), its owner, and two workers with Site access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlanPinTestBase.Storage.class)
abstract class PlanPinTestBase {
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
    @Autowired ObjectStorage storage;
    @Autowired PlatformTransactionManager txm;

    protected Api api;
    protected Members members;
    protected Session owner, worker, otherWorker;
    protected UUID orgId, siteId, zoneId, workerMembership, otherWorkerMembership;
    protected String org, site, plan, pins;

    @BeforeEach
    void setUpSite() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        siteId = UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}")));
        site = org + "/projects/" + siteId;

        worker = api.signUp();
        profiles.save(new UserProfile(worker.user(), "Ana Perez"));
        workerMembership = members.add(orgId, worker.user(), "WORKER");
        members.grantSite(siteId, workerMembership);

        otherWorker = api.signUp();
        otherWorkerMembership = members.add(orgId, otherWorker.user(), "WORKER");
        members.grantSite(siteId, otherWorkerMembership);

        plan = site + "/floor-plans/" + readyPlan(siteId, true);
        pins = plan + "/pins";
    }

    /** A READY floor plan of the Site, optionally with the "Slab A" zone; returns its id. */
    protected UUID readyPlan(UUID projectId, boolean withZone) {
        UUID ownerMembership = members.ownerMembership(orgId, owner.user());
        return new TransactionTemplate(txm).execute(s -> {
            FloorPlan fp = new FloorPlan(projects.findById(projectId).orElseThrow(),
                    memberships.findById(ownerMembership).orElseThrow(), "L1", null, null, 1, "image/png", 1000);
            fp.startProcessing();
            fp.markReady("rendered-" + fp.getId() + ".png", Instant.now());
            floorPlans.save(fp);
            if (withZone) {
                zoneId = zones.save(new FloorPlanZone(fp, memberships.findById(ownerMembership).orElseThrow(),
                        "Slab A", null, ZoneStatus.IN_PROGRESS, List.of(new double[]{0, 0}, new double[]{4, 0},
                        new double[]{4, 4}, new double[]{0, 4}))).getId();
            }
            return fp.getId();
        });
    }

    /** Requests a photo upload under {@code pinsPath} and stores JPEG bytes there; returns the key. */
    protected String upload(Session session, String pinsPath) throws Exception {
        String key = requestUpload(session, pinsPath);
        storage.put(key, new byte[]{1, 2, 3}, "image/jpeg");
        return key;
    }

    protected String upload(Session session) throws Exception {
        return upload(session, pins);
    }

    protected String requestUpload(Session session, String pinsPath) throws Exception {
        String body = api.post(session, pinsPath + "/photo-uploads",
                        "{\"contentType\":\"image/jpeg\",\"sizeBytes\":3}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.key");
    }

    protected ResultActions createIssue(Session session, String title, double x, double y, String... photoKeys)
            throws Exception {
        return api.post(session, pins, "{\"kind\":\"ISSUE\",\"x\":" + x + ",\"y\":" + y
                + ",\"title\":" + (title == null ? "null" : "\"" + title + "\"") + ",\"photoKeys\":" + json(photoKeys) + "}");
    }

    protected ResultActions createPhotoPin(Session session, String... photoKeys) throws Exception {
        return api.post(session, pins, "{\"kind\":\"PHOTO\",\"x\":2,\"y\":2,\"photoKeys\":" + json(photoKeys) + "}");
    }

    protected static String json(String... values) {
        return values.length == 0 ? "[]" : "[\"" + String.join("\",\"", values) + "\"]";
    }

    protected static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return value == null ? null : value.toString();
    }
}

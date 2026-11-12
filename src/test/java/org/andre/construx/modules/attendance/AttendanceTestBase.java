package org.andre.construx.modules.attendance;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.andre.construx.support.MutableClock;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A company with a Site "Tower", its owner, and two workers with Site access: {@code worker} is "Ana Perez",
 * {@code otherWorker} has no profile. The clock is a {@link MutableClock} that every test starts at
 * {@link #MORNING}, 09:00 on 10 March 2026 in the configured zone (America/Mexico_City, UTC-6); move it with
 * {@code clock.set(...)}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AttendanceTestBase.Time.class)
public abstract class AttendanceTestBase {
    public static final String MORNING = "2026-03-10T15:00:00Z";

    @TestConfiguration
    static class Time {
        @Bean @Primary MutableClock attendanceClock() {
            return new MutableClock(Instant.parse(MORNING));
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected UserAccountRepository users;
    @Autowired protected UserProfileRepository profiles;
    @Autowired protected TokenService tokens;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected OrganizationMembershipRepository memberships;
    @Autowired protected ProjectRepository projects;
    @Autowired protected ProjectAccessRepository accesses;
    @Autowired protected PlatformTransactionManager txm;
    @Autowired protected MutableClock clock;

    protected Api api;
    protected Members members;
    protected Session owner, worker, otherWorker;
    protected UUID orgId, siteId, ownerMembership, workerMembership, otherWorkerMembership;
    /** Paths: the company, the Site, its Attendance and its Check-ins. */
    protected String org, site, attendance, checkIns;

    @BeforeEach
    void setUpSite() throws Exception {
        clock.set(MORNING);
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        ownerMembership = members.ownerMembership(orgId, owner.user());
        siteId = createSite("Tower");
        site = sitePath(siteId);
        attendance = site + "/attendance";
        checkIns = attendance + "/check-ins";

        worker = api.signUp();
        profiles.save(new UserProfile(worker.user(), "Ana Perez"));
        workerMembership = members.add(orgId, worker.user(), "WORKER");
        members.grantSite(siteId, workerMembership);

        otherWorker = api.signUp();
        otherWorkerMembership = members.add(orgId, otherWorker.user(), "WORKER");
        members.grantSite(siteId, otherWorkerMembership);
    }

    /** Another Site of the company; nobody but organization-wide roles can see it until granted. */
    protected UUID createSite(String name) throws Exception {
        return UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"" + name + "\"}")));
    }

    protected String sitePath(UUID id) {
        return org + "/projects/" + id;
    }

    /** The session checks itself in to the fixture Site. */
    protected ResultActions checkIn(Session session) throws Exception {
        return api.post(session, checkIns, "{}");
    }

    protected static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return value == null ? null : value.toString();
    }
}

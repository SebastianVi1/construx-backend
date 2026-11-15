package org.andre.construx.modules.attendance;

import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checking in someone else: a member with {@code MANAGE_PLAN} on the Site, for any active member of the company,
 * or the leader of a Crew, for members of that Crew.
 */
class CheckInForOthersFlowTest extends AttendanceZoneTestBase {
    /** A Member with access to the Site: {@code MANAGE_PLAN} on it, no Crew. */
    private Session planner;
    private UUID plannerMembership;
    /** A Worker of the company with no access to the Site; a member of the Crew below. */
    private Session helper;
    private UUID helperMembership;
    /** A Worker with access to the Site who leads the Crew below. */
    private Session crewLeader;
    private UUID crewLeaderMembership;
    /** A Worker with access to the Site, in no Crew. */
    private Session outsider;
    private UUID outsiderMembership;
    /** A Worker with access to the Site who leads another Crew. */
    private Session otherLeader;
    private UUID otherLeaderMembership;

    @BeforeEach
    void setUpPeople() throws Exception {
        planner = api.signUp();
        plannerMembership = members.add(orgId, planner.user(), "MEMBER");
        members.grantSite(siteId, plannerMembership);

        helper = api.signUp();
        helperMembership = members.add(orgId, helper.user(), "WORKER");

        crewLeader = api.signUp();
        crewLeaderMembership = members.add(orgId, crewLeader.user(), "WORKER");
        members.grantSite(siteId, crewLeaderMembership);

        outsider = api.signUp();
        outsiderMembership = members.add(orgId, outsider.user(), "WORKER");
        members.grantSite(siteId, outsiderMembership);

        otherLeader = api.signUp();
        otherLeaderMembership = members.add(orgId, otherLeader.user(), "WORKER");
        members.grantSite(siteId, otherLeaderMembership);

        // The Crew of this ticket: led by crewLeader, its members are worker and helper.
        crew("Rebar crew", crewLeaderMembership, workerMembership, helperMembership);
        crew("Other crew", otherLeaderMembership, otherWorkerMembership);
    }

    @Test
    void aPlannerChecksInACompanyMemberWithoutAccessToTheSite() throws Exception {
        api.post(planner, checkIns, body(helperMembership, slabA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(helperMembership.toString()))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(plannerMembership.toString()))
                .andExpect(jsonPath("$.zone.id").value(slabA.toString()))
                .andExpect(jsonPath("$.date").value("2026-03-10"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(row(helperMembership, "state", "UNPLANNED"))
                .andExpect(row(helperMembership, "checkIn.zone.id", slabA.toString()))
                .andExpect(row(helperMembership, "checkIn.recordedBy.membershipId", plannerMembership.toString()))
                .andExpect(row(helperMembership, "checkIn.recordedBy.name", planner.user().getEmail()));
    }

    @Test
    void theMemberWithoutAccessToTheSiteCanNeitherReadAttendanceNorCheckThemselvesIn() throws Exception {
        api.get(helper, attendance).andExpect(status().isForbidden());
        api.post(helper, checkIns, "{}").andExpect(status().isForbidden());
    }

    @Test
    void aMemberWithoutTheRightCannotCheckInSomeoneElse() throws Exception {
        api.post(worker, checkIns, body(otherWorkerMembership, null)).andExpect(status().isForbidden());

        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aCrewLeaderChecksInTheirCrewsMembers() throws Exception {
        api.post(crewLeader, checkIns, body(helperMembership, slabB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(helperMembership.toString()))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(crewLeaderMembership.toString()))
                .andExpect(jsonPath("$.zone.id").value(slabB.toString()));
        api.post(crewLeader, checkIns, body(workerMembership, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()));

        api.get(crewLeader, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(row(helperMembership, "checkIn.recordedBy.membershipId", crewLeaderMembership.toString()))
                .andExpect(row(workerMembership, "checkIn.recordedBy.membershipId", crewLeaderMembership.toString()));
    }

    @Test
    void aCrewLeaderCannotCheckInSomeoneOutsideTheirCrew() throws Exception {
        api.post(crewLeader, checkIns, body(outsiderMembership, null)).andExpect(status().isForbidden());
        api.post(crewLeader, checkIns, body(otherWorkerMembership, null)).andExpect(status().isForbidden());
        api.post(otherLeader, checkIns, body(workerMembership, null)).andExpect(status().isForbidden());

        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aCrewLeaderWithoutAccessToTheSiteCannotCheckInTheirCrew() throws Exception {
        Session leader = api.signUp();
        UUID membership = members.add(orgId, leader.user(), "WORKER");
        crew("Paper crew", membership, helperMembership);

        api.post(leader, checkIns, body(helperMembership, null)).andExpect(status().isForbidden());
    }

    @Test
    void checkingInSomeoneAlreadyInThatPlaceRecordsNothing() throws Exception {
        String first = read(api.post(planner, checkIns, body(helperMembership, slabA))
                .andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");

        api.post(planner, checkIns, body(helperMembership, slabA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(plannerMembership.toString()));
    }

    @Test
    void anotherRecorderMovesTheSamePersonAndTheLatestRecorderShows() throws Exception {
        String first = read(api.post(planner, checkIns, body(helperMembership, slabA))
                .andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");

        api.post(crewLeader, checkIns, body(helperMembership, slabB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(first)))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(crewLeaderMembership.toString()));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(row(helperMembership, "checkIn.zone.id", slabB.toString()))
                .andExpect(row(helperMembership, "checkIn.recordedBy.membershipId", crewLeaderMembership.toString()));
    }

    @Test
    void checkingYourselfInIsUnchanged() throws Exception {
        api.post(worker, checkIns, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(workerMembership.toString()));

        clock.set("2026-03-10T17:00:00Z");
        api.post(worker, checkIns, body(workerMembership, slabA)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.recordedBy.membershipId").value(workerMembership.toString()));
    }

    @Test
    void theCallerIsToldWhetherTheyMayCheckInOthers() throws Exception {
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.canCheckInOthers").value(true));
        api.get(planner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.canCheckInOthers").value(true));
        api.get(crewLeader, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.canCheckInOthers").value(true));
        api.get(worker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.canCheckInOthers").value(false));
        api.get(outsider, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.canCheckInOthers").value(false));
    }

    @Test
    void anAbsentRowOnlyOffersCheckInWhenThisCallerMayCheckInThatPerson() throws Exception {
        String assignments = site + "/floor-plans/" + planId + "/zones/" + slabA + "/assignments";
        api.post(owner, assignments, "{" + person(workerMembership) + "}").andExpect(status().isCreated());
        api.post(owner, assignments, "{" + person(outsiderMembership) + "}").andExpect(status().isCreated());

        api.get(crewLeader, attendance).andExpect(status().isOk())
                .andExpect(row(workerMembership, "checkInAllowed", true))
                .andExpect(row(outsiderMembership, "checkInAllowed", false));
        api.get(planner, attendance).andExpect(status().isOk())
                .andExpect(row(workerMembership, "checkInAllowed", true))
                .andExpect(row(outsiderMembership, "checkInAllowed", true));
        api.post(crewLeader, checkIns, body(outsiderMembership, null)).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownSuspendedOrDepartedMemberIsNotFound() throws Exception {
        UUID suspended = members.add(orgId, api.signUp().user(), "WORKER");
        UUID departed = members.add(orgId, api.signUp().user(), "WORKER");
        new TransactionTemplate(txm).executeWithoutResult(
                s -> memberships.findById(suspended).orElseThrow().suspend());
        new TransactionTemplate(txm).executeWithoutResult(
                s -> memberships.findById(departed).orElseThrow().leave());

        api.post(planner, checkIns, body(UUID.randomUUID(), null)).andExpect(status().isNotFound());
        api.post(planner, checkIns, body(suspended, null)).andExpect(status().isNotFound());
        api.post(planner, checkIns, body(departed, slabA)).andExpect(status().isNotFound());
        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aCrewLeaderCannotCheckInACrewMemberWhoIsNoLongerActive() throws Exception {
        new TransactionTemplate(txm).executeWithoutResult(
                s -> memberships.findById(workerMembership).orElseThrow().suspend());

        api.post(crewLeader, checkIns, body(workerMembership, null)).andExpect(status().isNotFound());
    }

    @Test
    void aMemberOfAnotherCompanyIsNotFound() throws Exception {
        Session stranger = api.signUp();
        UUID otherOrg = api.createOrganization(stranger, "Other " + UUID.randomUUID());
        UUID strangerMembership = members.ownerMembership(otherOrg, stranger.user());

        api.post(planner, checkIns, body(strangerMembership, null)).andExpect(status().isNotFound());
    }

    private static String body(UUID membershipId, UUID zoneId) {
        return "{" + person(membershipId) + ",\"zoneId\":" + (zoneId == null ? "null" : "\"" + zoneId + "\"") + "}";
    }

    private static String person(UUID membershipId) {
        return "\"membershipId\":\"" + membershipId + "\"";
    }

    /** The owner creates a Crew led by its first member. */
    private String crew(String name, UUID leader, UUID... others) throws Exception {
        String crew = Api.id(api.post(owner, org + "/crews",
                "{\"name\":\"" + name + "\",\"leaderMembershipId\":\"" + leader + "\"}")
                .andExpect(status().isCreated()));
        for (UUID other : others) {
            api.post(owner, org + "/crews/" + crew + "/members", "{\"membershipId\":\"" + other + "\"}")
                    .andExpect(status().isOk());
        }
        return crew;
    }

    /** A field of one person's row; {@code expected} null means the field is null. */
    private static ResultMatcher row(UUID membershipId, String field, Object expected) {
        String path = "$.people[?(@.membershipId=='" + membershipId + "')]." + field;
        return expected == null ? jsonPath(path, contains(nullValue())) : jsonPath(path, contains(expected));
    }
}

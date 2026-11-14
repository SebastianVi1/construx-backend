package org.andre.construx.modules.attendance;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Attendance against the zone plan: present, absent and unplanned, for today and for past days. */
class AttendancePlanFlowTest extends AttendanceZoneTestBase {
    private static final String YESTERDAY_MORNING = "2026-03-09T15:00:00Z";

    @Test
    void peopleArePresentAbsentOrUnplannedAgainstThePlan() throws Exception {
        assign(slabA, person(workerMembership));
        assign(slabB, person(ownerMembership));
        checkIn(worker, slabA).andExpect(status().isCreated());
        checkIn(otherWorker, slabB).andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(3)))
                .andExpect(row(workerMembership, "state", "PRESENT"))
                .andExpect(row(workerMembership, "planned", true))
                .andExpect(row(workerMembership, "expectedZone.id", slabA.toString()))
                .andExpect(row(workerMembership, "checkIn.zone.id", slabA.toString()))
                .andExpect(row(ownerMembership, "state", "ABSENT"))
                .andExpect(row(ownerMembership, "planned", true))
                .andExpect(row(ownerMembership, "expectedZone.id", slabB.toString()))
                .andExpect(row(ownerMembership, "expectedZone.name", "Slab B"))
                .andExpect(row(ownerMembership, "expectedZone.floorPlanName", "Level 1"))
                .andExpect(row(ownerMembership, "checkIn", null))
                .andExpect(row(ownerMembership, "removable", false))
                .andExpect(row(otherWorkerMembership, "state", "UNPLANNED"))
                .andExpect(row(otherWorkerMembership, "planned", false))
                .andExpect(row(otherWorkerMembership, "expectedZone", null))
                .andExpect(row(otherWorkerMembership, "checkIn.zone.id", slabB.toString()));
    }

    @Test
    void anAbsentPersonIsNamedLikeAnyOtherAndRowsStayOrderedByName() throws Exception {
        profiles.save(new UserProfile(otherWorker.user(), "zoe Ruiz"));
        profiles.save(new UserProfile(owner.user(), "Mario Diaz"));
        assign(slabA, person(otherWorkerMembership));
        assign(slabA, person(workerMembership));
        checkIn(owner, null).andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].name", contains("Ana Perez", "Mario Diaz", "zoe Ruiz")))
                .andExpect(jsonPath("$.people[*].state", contains("ABSENT", "UNPLANNED", "ABSENT")));
    }

    @Test
    void anAbsentPersonWithoutAProfileNameIsShownByEmail() throws Exception {
        assign(slabA, person(otherWorkerMembership));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(row(otherWorkerMembership, "name", otherWorker.user().getEmail()));
    }

    @Test
    void aPersonExpectedInOneZoneWhoChecksInToAnotherIsPresentAndPlannedWithBothZones() throws Exception {
        assign(slabA, person(workerMembership));
        checkIn(worker, slabB).andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(row(workerMembership, "state", "PRESENT"))
                .andExpect(row(workerMembership, "planned", true))
                .andExpect(row(workerMembership, "expectedZone.id", slabA.toString()))
                .andExpect(row(workerMembership, "checkIn.zone.id", slabB.toString()));
    }

    @Test
    void aPlannedPersonWhoChecksInWithNoZoneIsPresent() throws Exception {
        assign(slabA, person(workerMembership));
        checkIn(worker, null).andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(row(workerMembership, "state", "PRESENT"))
                .andExpect(row(workerMembership, "expectedZone.id", slabA.toString()))
                .andExpect(row(workerMembership, "checkIn.zone", null));
    }

    @Test
    void aCrewAssignmentMakesEveryCrewMemberExpected() throws Exception {
        String crew = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crew + "\"");
        checkIn(otherWorker, slabB).andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(row(workerMembership, "state", "ABSENT"))
                .andExpect(row(workerMembership, "expectedZone.id", slabB.toString()))
                .andExpect(row(otherWorkerMembership, "state", "PRESENT"))
                .andExpect(row(otherWorkerMembership, "planned", true))
                .andExpect(row(otherWorkerMembership, "expectedZone.id", slabB.toString()));
    }

    @Test
    void equipmentIsNeverExpected() throws Exception {
        String equipment = Api.id(api.post(owner, org + "/equipment",
                "{\"type\":\"Crane\",\"identifier\":\"C-1\"}").andExpect(status().isCreated()));
        assign(slabA, "\"equipmentId\":\"" + equipment + "\"");

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aClosedAssignmentExpectsNobody() throws Exception {
        String assignment = assign(slabA, person(workerMembership));
        api.post(owner, site + "/floor-plans/" + planId + "/zones/" + slabA + "/assignments/" + assignment
                + "/close", "{}").andExpect(status().isOk());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void anAssignmentCoversFromItsStartToItsEndInclusive() throws Exception {
        assign(slabA, person(workerMembership) + ",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-09\"");
        assign(slabA, person(otherWorkerMembership) + ",\"startDate\":\"2026-03-11\"");
        assign(slabB, person(ownerMembership) + ",\"startDate\":\"2026-03-10\",\"endDate\":\"2026-03-10\"");

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(row(ownerMembership, "state", "ABSENT"));
    }

    @Test
    void anAssignmentWithoutDatesStartsTodayAndStaysOpen() throws Exception {
        assign(slabA, person(workerMembership));

        clock.set("2026-03-12T15:00:00Z");
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-12"))
                .andExpect(row(workerMembership, "state", "ABSENT"));
        api.get(owner, attendance + "?date=2026-03-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aPersonWithSeveralAssignmentsIsExpectedInTheOneThatStartedEarliest() throws Exception {
        assign(slabA, person(workerMembership) + ",\"startDate\":\"2026-03-09\"");
        assign(slabB, person(workerMembership) + ",\"startDate\":\"2026-03-08\"");
        String crew = crew("Rebar crew", workerMembership);
        assign(slabA, "\"crewId\":\"" + crew + "\",\"startDate\":\"2026-03-10\"");

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(row(workerMembership, "expectedZone.id", slabB.toString()));
    }

    @Test
    void assignmentsOfAnotherSiteOrOfADeletedZoneExpectNobodyHere() throws Exception {
        UUID annex = createSite("Annex");
        UUID annexPlan = floorPlan(annex, "Annex plan");
        UUID yard = zone(annexPlan, "Yard");
        api.post(owner, sitePath(annex) + "/floor-plans/" + annexPlan + "/zones/" + yard + "/assignments",
                "{" + person(workerMembership) + "}").andExpect(status().isCreated());
        assign(slabA, person(otherWorkerMembership));
        deleteZone(siteId, planId, slabA).andExpect(status().isNoContent());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)));
        api.get(owner, sitePath(annex) + "/attendance").andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].membershipId", contains(workerMembership.toString())));
    }

    @Test
    void anExpectedMemberWhoIsNoLongerActiveIsNotAbsent() throws Exception {
        assign(slabA, person(workerMembership));
        new TransactionTemplate(txm).executeWithoutResult(
                s -> memberships.findById(workerMembership).orElseThrow().suspend());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void removingTheOnlyCheckInMakesAPlannedPersonAbsentAgain() throws Exception {
        assign(slabA, person(workerMembership));
        String id = read(checkIn(worker, slabA), "$.id");
        api.delete(worker, checkIns + "/" + id).andExpect(status().isNoContent());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(row(workerMembership, "state", "ABSENT"))
                .andExpect(row(workerMembership, "checkIn", null));
    }

    @Test
    void theCallerIsToldTheirOwnExpectedZone() throws Exception {
        assign(slabB, person(workerMembership));

        api.get(worker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.me.expectedZone.id").value(slabB.toString()))
                .andExpect(jsonPath("$.me.expectedZone.name").value("Slab B"))
                .andExpect(jsonPath("$.me.expectedZone.floorPlanId").value(planId.toString()))
                .andExpect(jsonPath("$.me.expectedZone.floorPlanName").value("Level 1"));
        api.get(otherWorker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.expectedZone").value(nullValue()));
    }

    @Test
    void aPastDayIsShownAgainstThePlanOfThatDayAndIsReadOnly() throws Exception {
        clock.set(YESTERDAY_MORNING);
        assign(slabA, person(workerMembership) + ",\"endDate\":\"2026-03-09\"");
        assign(slabA, person(ownerMembership));
        checkIn(worker, slabA).andExpect(status().isCreated());
        checkIn(otherWorker, null).andExpect(status().isCreated());
        clock.set(MORNING);
        checkIn(otherWorker, slabB).andExpect(status().isCreated());

        api.get(owner, attendance + "?date=2026-03-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-09"))
                .andExpect(jsonPath("$.today").value(false))
                .andExpect(jsonPath("$.people", hasSize(3)))
                .andExpect(row(workerMembership, "state", "PRESENT"))
                .andExpect(row(workerMembership, "removable", false))
                .andExpect(row(ownerMembership, "state", "ABSENT"))
                .andExpect(row(otherWorkerMembership, "state", "UNPLANNED"))
                .andExpect(row(otherWorkerMembership, "checkIn.zone", null))
                .andExpect(row(otherWorkerMembership, "removable", false));
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.today").value(true))
                .andExpect(jsonPath("$.people[*].membershipId",
                        containsInAnyOrder(ownerMembership.toString(), otherWorkerMembership.toString())));
    }

    @Test
    void askingForTodayByDateIsTheSameAsAskingWithoutOne() throws Exception {
        checkIn(worker).andExpect(status().isCreated());

        api.get(worker, attendance + "?date=2026-03-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.today").value(true))
                .andExpect(row(workerMembership, "removable", true));
    }

    @Test
    void aFutureDateIsRefused() throws Exception {
        api.get(owner, attendance + "?date=2026-03-11").andExpect(status().isBadRequest());
        // 23:30 in the configured zone is still 10 March, although it is already 11 March in UTC.
        clock.set("2026-03-11T05:30:00Z");
        api.get(owner, attendance + "?date=2026-03-11").andExpect(status().isBadRequest());
        clock.set("2026-03-11T06:30:00Z");
        api.get(owner, attendance + "?date=2026-03-11").andExpect(status().isOk());
    }

    @Test
    void aMalformedDateIsRefused() throws Exception {
        api.get(owner, attendance + "?date=10-03-2026").andExpect(status().isBadRequest());
        api.get(owner, attendance + "?date=yesterday").andExpect(status().isBadRequest());
        api.get(owner, attendance + "?date=2026-02-30").andExpect(status().isBadRequest());
        api.get(owner, attendance + "?date=").andExpect(status().isBadRequest());
    }

    @Test
    void aDayWithNobodyExpectedAndNobodyPresentHasNoRows() throws Exception {
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(0)))
                .andExpect(jsonPath("$.me.expectedZone").value(nullValue()));
    }

    @Test
    void aMemberWhoCannotViewTheSiteCannotReadAPastDay() throws Exception {
        UUID annex = createSite("Annex");

        api.get(worker, sitePath(annex) + "/attendance?date=2026-03-09").andExpect(status().isForbidden());
        api.get(api.signUp(), attendance + "?date=2026-03-09").andExpect(status().isNotFound());
    }

    private static String person(UUID membershipId) {
        return "\"membershipId\":\"" + membershipId + "\"";
    }

    /** The owner assigns to a zone of "Level 1"; {@code fields} is the inside of the JSON body. Returns the id. */
    private String assign(UUID zone, String fields) throws Exception {
        ResultActions created = api.post(owner, site + "/floor-plans/" + planId + "/zones/" + zone
                + "/assignments", "{" + fields + "}").andExpect(status().isCreated());
        return read(created, "$.assignment.id");
    }

    /** A Crew led by its first member. */
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

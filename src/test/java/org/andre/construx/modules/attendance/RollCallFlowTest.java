package org.andre.construx.modules.attendance;

import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RollCallFlowTest extends AttendanceZoneTestBase {
    @MockitoSpyBean CheckInRepository checkInRows;

    @Test
    void aCrewLeaderSeesAndRecordsTheExpectedCrewInOneRollCall() throws Exception {
        String crewId = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crewId + "\"");

        api.get(worker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.rollCallCrews", hasSize(1)))
                .andExpect(jsonPath("$.me.rollCallCrews[0].crewId").value(crewId))
                .andExpect(jsonPath("$.me.rollCallCrews[0].name").value("Rebar crew"))
                .andExpect(jsonPath("$.me.rollCallCrews[0].expectedZone.id").value(slabB.toString()))
                .andExpect(jsonPath("$.me.rollCallCrews[0].members", hasSize(2)))
                .andExpect(jsonPath("$.me.rollCallCrews[0].members[0].membershipId").exists())
                .andExpect(jsonPath("$.me.rollCallCrews[0].members[0].name").exists())
                .andExpect(jsonPath("$.me.rollCallCrews[0].members[0].member").doesNotExist())
                .andExpect(jsonPath("$.me.rollCallCrews[0].members[0].checkIn").value(nullValue()));

        api.post(worker, attendance + "/roll-calls", rollCall(crewId,
                "\"" + workerMembership + "\",\"" + otherWorkerMembership + "\"", slabB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].recordedBy.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$[0].zone.id").value(slabB.toString()));
    }

    @Test
    void attendanceReusesTheLatestCheckInForTheRollCallRoster() throws Exception {
        String crewId = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crewId + "\"");
        checkIn(worker, slabA).andExpect(status().isCreated());
        clock.set("2026-03-10T15:01:00Z");
        checkIn(worker, slabB).andExpect(status().isCreated());
        clearInvocations(checkInRows);

        api.get(worker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].checkIn.zone.id", hasItem(slabB.toString())))
                .andExpect(jsonPath("$.me.rollCallCrews[0].members[*].checkIn.zone.id", hasItem(slabB.toString())));

        verify(checkInRows, times(1)).findOfDay(eq(siteId), eq(LocalDate.parse("2026-03-10")));
    }

    @Test
    void aNonmemberRejectsTheWholeRollCallBeforeAnyCheckInIsStored() throws Exception {
        String crewId = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crewId + "\"");
        long before = checkInRows.count();

        api.post(worker, attendance + "/roll-calls", rollCall(crewId,
                "\"" + workerMembership + "\",\"" + ownerMembership + "\"", slabB))
                .andExpect(status().isBadRequest());

        assertEquals(before, checkInRows.count());
    }

    @Test
    void emptyRollCallIsRejectedAndDuplicateMembersAreCheckedInOnlyOnce() throws Exception {
        String crewId = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crewId + "\"");

        api.post(worker, attendance + "/roll-calls", rollCall(crewId, "", slabB))
                .andExpect(status().isBadRequest());

        api.post(worker, attendance + "/roll-calls", rollCall(crewId,
                "\"" + workerMembership + "\",\"" + workerMembership + "\"", slabB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].membershipId").value(workerMembership.toString()));

        long afterFirstRollCall = checkInRows.count();
        api.post(worker, attendance + "/roll-calls", rollCall(crewId,
                "\"" + workerMembership + "\"", slabB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)));
        assertEquals(afterFirstRollCall, checkInRows.count());
    }

    @Test
    void onlyTheCrewLeaderOrPlanManagerCanMakeARollCall() throws Exception {
        String crewId = crew("Rebar crew", workerMembership, otherWorkerMembership);
        assign(slabB, "\"crewId\":\"" + crewId + "\"");
        long before = checkInRows.count();

        api.post(otherWorker, attendance + "/roll-calls", rollCall(crewId,
                "\"" + workerMembership + "\"", slabB)).andExpect(status().isForbidden());
        assertEquals(before, checkInRows.count());
    }

    @Test
    void aPlanManagerCanTakeRollForAnExpectedCrewButNotAnUnassignedCrew() throws Exception {
        String expectedCrew = crew("Rebar crew", workerMembership, otherWorkerMembership);
        String unassignedCrew = crew("Paper crew", workerMembership);
        assign(slabB, "\"crewId\":\"" + expectedCrew + "\"");

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.me.rollCallCrews", hasSize(1)))
                .andExpect(jsonPath("$.me.rollCallCrews[0].crewId").value(expectedCrew));
        api.get(owner, attendance + "?date=2026-03-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.me.rollCallCrews", hasSize(0)));

        api.post(owner, attendance + "/roll-calls", rollCall(expectedCrew,
                "\"" + workerMembership + "\"", slabB)).andExpect(status().isCreated());
        long before = checkInRows.count();
        api.post(owner, attendance + "/roll-calls", rollCall(unassignedCrew,
                "\"" + workerMembership + "\"", slabB)).andExpect(status().isNotFound());
        assertEquals(before, checkInRows.count());
    }

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

    private String assign(UUID zone, String fields) throws Exception {
        return read(api.post(owner, site + "/floor-plans/" + planId + "/zones/" + zone + "/assignments",
                "{" + fields + "}").andExpect(status().isCreated()), "$.assignment.id");
    }

    private static String rollCall(String crewId, String membershipIds, UUID zoneId) {
        return "{\"crewId\":\"" + crewId + "\",\"membershipIds\":[" + membershipIds
                + "],\"zoneId\":\"" + zoneId + "\"}";
    }
}

package org.andre.construx.modules.attendance;

import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZoneCheckInFlowTest extends AttendanceZoneTestBase {
    @Autowired CheckInRepository checkInRows;

    @Test
    void theSitesZonesAreListedWithTheirFloorPlan() throws Exception {
        UUID basement = floorPlan(siteId, "basement");
        UUID ramp = zone(basement, "Ramp");
        UUID annex = createSite("Annex");
        zone(floorPlan(annex, "Annex plan"), "Annex yard");

        api.get(worker, zones).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Ramp", "Slab A", "Slab B")))
                .andExpect(jsonPath("$[0].id").value(ramp.toString()))
                .andExpect(jsonPath("$[0].floorPlanId").value(basement.toString()))
                .andExpect(jsonPath("$[0].floorPlanName").value("basement"))
                .andExpect(jsonPath("$[1].id").value(slabA.toString()))
                .andExpect(jsonPath("$[1].floorPlanId").value(planId.toString()))
                .andExpect(jsonPath("$[1].floorPlanName").value("Level 1"));
    }

    @Test
    void aDeletedZoneIsNotOffered() throws Exception {
        deleteZone(siteId, planId, slabA).andExpect(status().isNoContent());

        api.get(worker, zones).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Slab B")));
    }

    @Test
    void aSiteWithoutFloorPlansHasNoZones() throws Exception {
        UUID annex = createSite("Annex");

        api.get(owner, sitePath(annex) + "/attendance/zones").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aMemberWhoCannotViewTheSiteCannotListItsZones() throws Exception {
        UUID annex = createSite("Annex");

        api.get(worker, sitePath(annex) + "/attendance/zones").andExpect(status().isForbidden());
        api.get(api.signUp(), zones).andExpect(status().isNotFound());
    }

    @Test
    void checkingInToAZoneShowsThePersonInThatZone() throws Exception {
        checkIn(worker, slabA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone.id").value(slabA.toString()))
                .andExpect(jsonPath("$.zone.name").value("Slab A"))
                .andExpect(jsonPath("$.zone.floorPlanId").value(planId.toString()))
                .andExpect(jsonPath("$.zone.floorPlanName").value("Level 1"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.zone.id").value(slabA.toString()))
                .andExpect(jsonPath("$.people[0].checkIn.zone.name").value("Slab A"));
    }

    @Test
    void checkingInWithNoZoneLeavesThePersonPresentWithNoZone() throws Exception {
        checkIn(worker, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone").value(nullValue()));
        checkIn(otherWorker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone").value(nullValue()));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(jsonPath("$.people[0].checkIn.zone").value(nullValue()));
    }

    @Test
    void aSecondCheckInToAnotherZoneMovesThePerson() throws Exception {
        String first = read(checkIn(worker, slabA).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");

        checkIn(worker, slabB).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(first)))
                .andExpect(jsonPath("$.zone.name").value("Slab B"))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T17:00:00Z"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].state").value("UNPLANNED"))
                .andExpect(jsonPath("$.people[0].checkIn.id", not(first)))
                .andExpect(jsonPath("$.people[0].checkIn.zone.name").value("Slab B"))
                .andExpect(jsonPath("$.people[0].checkIn.checkedInAt").value("2026-03-10T17:00:00Z"));
    }

    @Test
    void aCheckInWithNoZoneAfterOneWithAZoneLeavesThePersonPresentWithNoZone() throws Exception {
        String first = read(checkIn(worker, slabA).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");

        checkIn(worker, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(first)))
                .andExpect(jsonPath("$.zone").value(nullValue()));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].state").value("UNPLANNED"))
                .andExpect(jsonPath("$.people[0].checkIn.zone").value(nullValue()))
                .andExpect(jsonPath("$.people[0].checkIn.checkedInAt").value("2026-03-10T17:00:00Z"));
    }

    @Test
    void checkingInToTheCurrentZoneAgainCreatesNothing() throws Exception {
        String first = read(checkIn(worker, slabA).andExpect(status().isCreated()), "$.id");
        long stored = checkInRows.count();
        clock.set("2026-03-10T17:00:00Z");

        checkIn(worker, slabA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.zone.name").value("Slab A"))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T15:00:00Z"));

        assertEquals(stored, checkInRows.count());
    }

    @Test
    void checkingInWithNoZoneAgainCreatesNothing() throws Exception {
        String first = read(checkIn(worker, null).andExpect(status().isCreated()), "$.id");
        long stored = checkInRows.count();

        checkIn(worker, null).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
        checkIn(worker).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));

        assertEquals(stored, checkInRows.count());
    }

    @Test
    void goingBackToAnEarlierZoneIsANewCheckIn() throws Exception {
        String first = read(checkIn(worker, slabA).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");
        checkIn(worker, slabB).andExpect(status().isCreated());
        clock.set("2026-03-10T19:00:00Z");

        checkIn(worker, slabA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(first)))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T19:00:00Z"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.zone.name").value("Slab A"));
    }

    @Test
    void anUnknownZoneIsNotFoundAndNothingIsStored() throws Exception {
        long stored = checkInRows.count();

        checkIn(worker, UUID.randomUUID()).andExpect(status().isNotFound());

        assertEquals(stored, checkInRows.count());
        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aDeletedZoneIsNotFoundAndNothingIsStored() throws Exception {
        deleteZone(siteId, planId, slabA).andExpect(status().isNoContent());
        long stored = checkInRows.count();

        checkIn(worker, slabA).andExpect(status().isNotFound());

        assertEquals(stored, checkInRows.count());
        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aZoneOfAnotherSiteIsNotFoundAndNothingIsStored() throws Exception {
        UUID annex = createSite("Annex");
        members.grantSite(annex, workerMembership);
        UUID annexYard = zone(floorPlan(annex, "Annex plan"), "Annex yard");
        long stored = checkInRows.count();

        checkIn(worker, annexYard).andExpect(status().isNotFound());

        assertEquals(stored, checkInRows.count());
        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aCheckInKeepsShowingItsZonesNameAfterTheZoneIsDeleted() throws Exception {
        String id = read(checkIn(worker, slabA).andExpect(status().isCreated()), "$.id");

        deleteZone(siteId, planId, slabA).andExpect(status().isNoContent());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.id").value(id))
                .andExpect(jsonPath("$.people[0].checkIn.zone.id").value(slabA.toString()))
                .andExpect(jsonPath("$.people[0].checkIn.zone.name").value("Slab A"))
                .andExpect(jsonPath("$.people[0].checkIn.zone.floorPlanName").value("Level 1"));
    }

    @Test
    void aPersonStillInADeletedZoneCanMoveOutOfIt() throws Exception {
        checkIn(worker, slabA).andExpect(status().isCreated());
        deleteZone(siteId, planId, slabA).andExpect(status().isNoContent());

        checkIn(worker, slabA).andExpect(status().isNotFound());
        checkIn(worker, slabB).andExpect(status().isCreated())
                .andExpect(jsonPath("$.zone.name").value("Slab B"));
    }
}

package org.andre.construx.modules.attendance;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.andre.construx.shared.audit.AuditEventRepository;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A mistaken Check-in is removed on its own day by who recorded it or by a member with {@code MANAGE_PLAN}. */
class RemoveCheckInFlowTest extends AttendanceTestBase {
    @Autowired CheckInRepository checkInRecords;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void whoRecordedACheckInRemovesItAndThePersonIsNoLongerPresent() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T16:00:00Z");

        remove(worker, id).andExpect(status().isNoContent());

        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void theRecordKeepsWhoRemovedItAndWhen() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T16:00:00Z");

        remove(owner, id).andExpect(status().isNoContent());

        inTransaction(() -> {
            CheckIn kept = checkInRecords.findById(UUID.fromString(id)).orElseThrow();
            assertEquals(Instant.parse("2026-03-10T16:00:00Z"), kept.getRemovedAt());
            assertEquals(ownerMembership, kept.getRemovedBy().getId());
            assertEquals(Instant.parse(MORNING), kept.getCheckedInAt());
        });
    }

    @Test
    void aMemberWithManagePlanRemovesACheckInRecordedBySomeoneElse() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");

        remove(owner, id).andExpect(status().isNoContent());

        api.get(worker, attendance).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void removingWithoutTheRightIs403AndChangesNothing() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");

        remove(otherWorker, id).andExpect(status().isForbidden());

        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.id").value(id));
    }

    @Test
    void removingOnAnotherDayIs409AndTheCheckInStays() throws Exception {
        // 23:30 on 10 March local time, then 00:30 on 11 March.
        clock.set("2026-03-11T05:30:00Z");
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-11T06:30:00Z");

        remove(worker, id).andExpect(status().isConflict());
        remove(owner, id).andExpect(status().isConflict());

        inTransaction(() -> assertNull(checkInRecords.findById(UUID.fromString(id)).orElseThrow().getRemovedAt()));
    }

    @Test
    void removingAnAlreadyRemovedCheckInIs204WithNoChange() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T16:00:00Z");
        remove(worker, id).andExpect(status().isNoContent());
        clock.set("2026-03-10T18:00:00Z");

        remove(owner, id).andExpect(status().isNoContent());

        inTransaction(() -> {
            CheckIn kept = checkInRecords.findById(UUID.fromString(id)).orElseThrow();
            assertEquals(Instant.parse("2026-03-10T16:00:00Z"), kept.getRemovedAt());
            assertEquals(workerMembership, kept.getRemovedBy().getId());
        });
    }

    @Test
    void thePersonFallsBackToTheirPreviousCheckInOfTheDay() throws Exception {
        String first = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        // A later Check-in of the same person the same day, as a move makes one.
        UUID later = inTransaction(() -> checkInRecords.save(new CheckIn(
                projects.getReferenceById(siteId), memberships.getReferenceById(workerMembership),
                LocalDate.parse("2026-03-10"), Instant.parse("2026-03-10T17:00:00Z"),
                memberships.getReferenceById(workerMembership))).getId());
        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.id").value(later.toString()));

        remove(worker, later.toString()).andExpect(status().isNoContent());

        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.people[0].checkIn.id").value(first))
                .andExpect(jsonPath("$.people[0].checkIn.checkedInAt").value(MORNING));
    }

    @Test
    void aPersonChecksInAgainAfterTheirCheckInWasRemoved() throws Exception {
        String removed = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        remove(worker, removed).andExpect(status().isNoContent());
        clock.set("2026-03-10T17:00:00Z");

        checkIn(worker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(removed)))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T17:00:00Z"));

        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)));
    }

    @Test
    void attendanceTellsWhichRowsTheCallerMayRemove() throws Exception {
        checkIn(worker).andExpect(status().isCreated());
        checkIn(otherWorker).andExpect(status().isCreated());
        String ana = "$.people[?(@.membershipId == '" + workerMembership + "')].removable";
        String other = "$.people[?(@.membershipId == '" + otherWorkerMembership + "')].removable";

        api.get(worker, attendance).andExpect(jsonPath(ana).value(true)).andExpect(jsonPath(other).value(false));
        api.get(owner, attendance).andExpect(jsonPath(ana).value(true)).andExpect(jsonPath(other).value(true));
    }

    @Test
    void anUnknownCheckInOrOneOfAnotherSiteIs404() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        UUID annex = createSite("Annex");

        remove(owner, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        api.delete(owner, sitePath(annex) + "/attendance/check-ins/" + id).andExpect(status().isNotFound());

        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)));
    }

    @Test
    void removalFollowsTheAccessRulesOfTheSite() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        Session stranger = api.signUp();
        Session outsider = api.signUp();
        members.add(orgId, outsider.user(), "WORKER");

        remove(stranger, id).andExpect(status().isNotFound());
        remove(outsider, id).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.delete(checkIns + "/" + id).cookie(worker.access()))
                .andExpect(status().isForbidden());

        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(1)));
    }

    @Test
    void aRemovalIsNotWrittenToTheAuditTable() throws Exception {
        String id = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        long before = auditEvents.count();

        remove(worker, id).andExpect(status().isNoContent());

        assertEquals(before, auditEvents.count());
    }

    private ResultActions remove(Session session, String checkInId) throws Exception {
        return api.delete(session, checkIns + "/" + checkInId);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txm).executeWithoutResult(status -> work.run());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txm).execute(status -> work.get());
    }
}

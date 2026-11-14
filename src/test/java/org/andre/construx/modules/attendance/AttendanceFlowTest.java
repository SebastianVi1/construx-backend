package org.andre.construx.modules.attendance;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.shared.audit.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttendanceFlowTest extends AttendanceTestBase {
    @Autowired AuditEventRepository auditEvents;

    @Test
    void aWorkerWhoChecksInIsPresentToday() throws Exception {
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.today").value(true))
                .andExpect(jsonPath("$.people", hasSize(0)));

        checkIn(worker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T15:00:00Z"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.people[0].name").value("Ana Perez"))
                .andExpect(jsonPath("$.people[0].state").value("UNPLANNED"))
                .andExpect(jsonPath("$.people[0].checkIn.checkedInAt").value("2026-03-10T15:00:00Z"))
                .andExpect(jsonPath("$.people[0].checkIn.recordedBy.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.people[0].checkIn.recordedBy.name").value("Ana Perez"))
                .andExpect(jsonPath("$.me.membershipId").value(ownerMembership.toString()));
    }

    @Test
    void aPersonWithoutAProfileNameIsShownByEmail() throws Exception {
        checkIn(otherWorker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordedBy.name").value(otherWorker.user().getEmail()));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[0].name").value(otherWorker.user().getEmail()))
                .andExpect(jsonPath("$.people[0].checkIn.recordedBy.name").value(otherWorker.user().getEmail()));
    }

    @Test
    void checkingInAgainCreatesNoSecondRecord() throws Exception {
        String first = read(checkIn(worker).andExpect(status().isCreated()), "$.id");
        clock.set("2026-03-10T17:00:00Z");

        checkIn(worker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-10T15:00:00Z"));

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].checkIn.id").value(first));
    }

    @Test
    void aPersonCheckedInOnTwoSitesIsPresentOnEach() throws Exception {
        UUID annex = createSite("Annex");
        members.grantSite(annex, workerMembership);
        String annexAttendance = sitePath(annex) + "/attendance";

        checkIn(worker).andExpect(status().isCreated());
        api.get(owner, annexAttendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(0)));

        api.post(worker, annexAttendance + "/check-ins", "{}").andExpect(status().isCreated());

        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].membershipId").value(workerMembership.toString()));
        api.get(owner, annexAttendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(1)))
                .andExpect(jsonPath("$.people[0].membershipId").value(workerMembership.toString()));
    }

    @Test
    void peopleAreListedByName() throws Exception {
        profiles.save(new UserProfile(otherWorker.user(), "Zoe Ruiz"));
        profiles.save(new UserProfile(owner.user(), "bruno Diaz"));
        checkIn(otherWorker).andExpect(status().isCreated());
        checkIn(owner).andExpect(status().isCreated());
        checkIn(worker).andExpect(status().isCreated());

        api.get(worker, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[*].name", contains("Ana Perez", "bruno Diaz", "Zoe Ruiz")))
                .andExpect(jsonPath("$.me.membershipId").value(workerMembership.toString()));
    }

    @Test
    void aCheckInBelongsToTheLocalDayNotTheUtcDay() throws Exception {
        // 23:30 on 10 March in Mexico City; a UTC server already calls it 11 March.
        clock.set("2026-03-11T05:30:00Z");
        checkIn(worker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.checkedInAt").value("2026-03-11T05:30:00Z"));
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-10"))
                .andExpect(jsonPath("$.people", hasSize(1)));
    }

    @Test
    void aNewDayStartsEmptyAndAcceptsANewCheckIn() throws Exception {
        clock.set("2026-03-11T05:30:00Z");
        String evening = read(checkIn(worker).andExpect(status().isCreated()), "$.id");

        // 00:30 on 11 March local time.
        clock.set("2026-03-11T06:30:00Z");
        api.get(owner, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-03-11"))
                .andExpect(jsonPath("$.today").value(true))
                .andExpect(jsonPath("$.people", hasSize(0)));

        checkIn(worker).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(evening)))
                .andExpect(jsonPath("$.date").value("2026-03-11"));
        api.get(owner, attendance).andExpect(status().isOk()).andExpect(jsonPath("$.people", hasSize(1)));
    }

    @Test
    void aCheckInIsNotWrittenToTheAuditTable() throws Exception {
        long before = auditEvents.count();
        checkIn(worker).andExpect(status().isCreated());
        assertEquals(before, auditEvents.count());
    }
}

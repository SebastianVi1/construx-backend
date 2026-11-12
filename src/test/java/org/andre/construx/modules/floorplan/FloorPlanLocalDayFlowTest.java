package org.andre.construx.modules.floorplan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The plan's "today" is the day of the configured time zone (America/Mexico_City by default). It is 23:30 on
 * 10 March there, which a UTC server already calls 11 March.
 */
class FloorPlanLocalDayFlowTest extends PlanPinTestBase {
    @TestConfiguration
    static class Evening {
        @Bean @Primary Clock eveningClock() {
            return Clock.fixed(Instant.parse("2026-03-11T05:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void anAssignmentMadeInTheEveningStartsToday() throws Exception {
        api.post(owner, plan + "/zones/" + zoneId + "/assignments", "{\"membershipId\":\"" + workerMembership + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignment.startDate").value("2026-03-10"));
    }

    @Test
    void thePlanStateWithoutADateIsTodays() throws Exception {
        String assignments = plan + "/zones/" + zoneId + "/assignments";
        api.post(owner, assignments, "{\"membershipId\":\"" + workerMembership + "\",\"startDate\":\"2026-03-11\"}")
                .andExpect(status().isCreated());
        api.get(owner, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeAssignments", hasSize(0)));

        api.post(owner, assignments, "{\"membershipId\":\"" + otherWorkerMembership
                        + "\",\"startDate\":\"2026-03-10\"}")
                .andExpect(status().isCreated());
        api.get(owner, plan + "/state").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activeAssignments", hasSize(1)))
                .andExpect(jsonPath("$[0].activeAssignments[0].membershipId").value(otherWorkerMembership.toString()));
    }

    @Test
    void assigningAnIssueInTheEveningPutsThePersonInTheZoneFromToday() throws Exception {
        api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":2,\"y\":2,\"title\":\"Leak\","
                + "\"assigneeMembershipId\":\"" + workerMembership + "\"}").andExpect(status().isCreated());

        api.get(owner, plan + "/zones/" + zoneId + "/assignments").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].startDate").value("2026-03-10"));
    }
}

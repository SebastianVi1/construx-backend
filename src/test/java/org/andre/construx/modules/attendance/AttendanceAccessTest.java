package org.andre.construx.modules.attendance;

import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seeing Attendance and checking oneself in need the Site to be visible to the caller ({@code VIEW_PROJECT}). */
class AttendanceAccessTest extends AttendanceTestBase {

    @Test
    void aNonMemberGets404() throws Exception {
        Session stranger = api.signUp();

        api.get(stranger, attendance).andExpect(status().isNotFound());
        checkIn(stranger).andExpect(status().isNotFound());
    }

    @Test
    void aSiteOfAnotherCompanyIs404() throws Exception {
        Session rival = api.signUp();
        UUID rivalOrg = api.createOrganization(rival, "Rival " + UUID.randomUUID());
        String foreign = "/api/organizations/" + rivalOrg + "/projects/" + siteId + "/attendance";

        api.get(rival, foreign).andExpect(status().isNotFound());
        api.post(rival, foreign + "/check-ins", "{}").andExpect(status().isNotFound());
        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void aWorkerWithoutAccessToTheSiteGets403() throws Exception {
        Session outsider = api.signUp();
        members.add(orgId, outsider.user(), "WORKER");

        api.get(outsider, attendance).andExpect(status().isForbidden());
        checkIn(outsider).andExpect(status().isForbidden());
        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(0)));
    }

    @Test
    void anOfficeRoleSeesAndChecksInOnAnySiteWithoutBeingAssignedToIt() throws Exception {
        Session finance = api.signUp();
        UUID financeMembership = members.add(orgId, finance.user(), "FINANCE");

        checkIn(finance).andExpect(status().isCreated());
        api.get(finance, attendance).andExpect(status().isOk())
                .andExpect(jsonPath("$.people[0].membershipId").value(financeMembership.toString()));
    }

    @Test
    void aSuspendedMemberGets404() throws Exception {
        new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(
                s -> memberships.findById(workerMembership).orElseThrow().suspend());

        api.get(worker, attendance).andExpect(status().isNotFound());
        checkIn(worker).andExpect(status().isNotFound());
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(attendance)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCheckInWithoutCsrfIsRejected() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(checkIns).cookie(worker.access())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        api.get(owner, attendance).andExpect(jsonPath("$.people", hasSize(0)));
    }
}

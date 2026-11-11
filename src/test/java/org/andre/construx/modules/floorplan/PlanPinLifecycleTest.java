package org.andre.construx.modules.floorplan;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The assignee resolves an issue, a manager verifies and closes it, and photos follow the same rights. */
class PlanPinLifecycleTest extends PlanPinTestBase {

    /** An open issue reported by the owner and assigned to {@code worker}; returns its path. */
    private String assignedIssue() throws Exception {
        return pins + "/" + read(api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"Leak\","
                + "\"assigneeMembershipId\":\"" + workerMembership + "\"}").andExpect(status().isCreated()), "$.id");
    }

    private static String to(String status, String... photoKeys) {
        return "{\"status\":\"" + status + "\",\"photoKeys\":" + json(photoKeys) + "}";
    }

    @Test
    void assigneeResolvesWithAResolutionPhoto() throws Exception {
        String pin = assignedIssue();
        String key = upload(worker);

        api.post(worker, pin + "/status", to("RESOLVED", key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").value(notNullValue()))
                .andExpect(jsonPath("$.photos", hasSize(1)))
                .andExpect(jsonPath("$.photos[0].phase").value("RESOLUTION"))
                .andExpect(jsonPath("$.can.resolve").value(false));
    }

    @Test
    void unassignedWorkerCannotResolve() throws Exception {
        api.post(otherWorker, assignedIssue() + "/status", to("RESOLVED")).andExpect(status().isForbidden());
    }

    @Test
    void workerCannotCloseOrReopen() throws Exception {
        String pin = assignedIssue();
        api.post(worker, pin + "/status", to("CLOSED")).andExpect(status().isForbidden());
        api.post(worker, pin + "/status", to("RESOLVED")).andExpect(status().isOk());
        api.post(worker, pin + "/status", to("OPEN")).andExpect(status().isForbidden());
    }

    @Test
    void managerClosesAndReopens() throws Exception {
        String pin = assignedIssue();
        api.post(worker, pin + "/status", to("RESOLVED")).andExpect(status().isOk());

        api.post(owner, pin + "/status", to("CLOSED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(notNullValue()))
                .andExpect(jsonPath("$.can.reopen").value(true))
                .andExpect(jsonPath("$.can.close").value(false));
        api.post(owner, pin + "/status", to("OPEN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.closedAt").value(nullValue()));
        api.get(owner, pin + "/audit").andExpect(jsonPath("$[0].action").value("STATUS"));
    }

    @Test
    void invalidTransitionIsRejected() throws Exception {
        String pin = assignedIssue();
        api.post(worker, pin + "/status", to("RESOLVED")).andExpect(status().isOk());

        api.post(owner, pin + "/status", to("RESOLVED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_INVALID_TRANSITION"));
    }

    @Test
    void statusOnAPhotoPinIsRejected() throws Exception {
        String pin = pins + "/" + read(createPhotoPin(worker, upload(worker)), "$.id");

        api.post(owner, pin + "/status", to("CLOSED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_NOT_AN_ISSUE"));
    }

    @Test
    void reporterLosesEditOnceResolved() throws Exception {
        ResultActions created = createIssue(worker, "Leak", 1, 1).andExpect(status().isCreated());
        String pin = pins + "/" + read(created, "$.id");
        String version = read(api.post(owner, pin + "/status", to("RESOLVED")).andExpect(status().isOk()),
                "$.version");

        api.patch(worker, pin, "{\"title\":\"Leak 2\",\"x\":1,\"y\":1,\"version\":" + version + "}")
                .andExpect(status().isForbidden());
        api.delete(worker, pin).andExpect(status().isForbidden());
        api.post(worker, pin + "/photos", "{\"keys\":" + json(upload(worker)) + ",\"phase\":\"REPORT\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void reporterAddsReportPhotosWhileOpen() throws Exception {
        String pin = pins + "/" + read(createIssue(worker, "Leak", 1, 1), "$.id");

        api.post(worker, pin + "/photos", "{\"keys\":" + json(upload(worker), upload(worker)) + ",\"phase\":\"REPORT\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos", hasSize(2)));
        api.post(otherWorker, pin + "/photos", "{\"keys\":" + json(upload(otherWorker)) + ",\"phase\":\"REPORT\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyTheAssigneeOrAManagerAddsResolutionPhotos() throws Exception {
        String pin = assignedIssue();

        api.post(otherWorker, pin + "/photos", "{\"keys\":" + json(upload(otherWorker)) + ",\"phase\":\"RESOLUTION\"}")
                .andExpect(status().isForbidden());
        api.post(worker, pin + "/photos", "{\"keys\":" + json(upload(worker)) + ",\"phase\":\"RESOLUTION\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos[0].phase").value("RESOLUTION"));
    }

    @Test
    void resolutionPhotosOnAPhotoPinAreRejected() throws Exception {
        String pin = pins + "/" + read(createPhotoPin(worker, upload(worker)), "$.id");

        api.post(owner, pin + "/photos", "{\"keys\":" + json(upload(owner)) + ",\"phase\":\"RESOLUTION\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_NOT_AN_ISSUE"));
    }

    @Test
    void photoLimitCountsPhotosAlreadyOnThePin() throws Exception {
        String[] nine = new String[9];
        for (int i = 0; i < nine.length; i++) nine[i] = upload(worker);
        String pin = pins + "/" + read(createIssue(worker, "Leak", 1, 1, nine), "$.id");

        api.post(worker, pin + "/photos", "{\"keys\":" + json(upload(worker), upload(worker)) + ",\"phase\":\"REPORT\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_PHOTO_LIMIT"));
    }

    @Test
    void deletingAPhotoRemovesItFromStorage() throws Exception {
        String key = upload(worker);
        ResultActions created = createIssue(worker, "Leak", 1, 1, key).andExpect(status().isCreated());
        String pin = pins + "/" + read(created, "$.id");
        String photo = pin + "/photos/" + read(created, "$.photos[0].id");

        api.delete(otherWorker, photo).andExpect(status().isForbidden());
        api.delete(worker, photo).andExpect(status().isNoContent());
        assertTrue(storage.head(key).isEmpty());
        api.get(worker, pin).andExpect(jsonPath("$.photos", hasSize(0)));
        api.delete(worker, photo).andExpect(status().isNotFound());
    }

    @Test
    void reporterCannotDeleteResolutionPhotos() throws Exception {
        String pin = pins + "/" + read(createIssue(worker, "Leak", 1, 1), "$.id");
        String photoId = read(api.post(owner, pin + "/photos", "{\"keys\":" + json(upload(owner))
                + ",\"phase\":\"RESOLUTION\"}").andExpect(status().isOk()), "$.photos[0].id");

        api.delete(worker, pin + "/photos/" + photoId).andExpect(status().isForbidden());
        api.delete(owner, pin + "/photos/" + photoId).andExpect(status().isNoContent());
    }

    @Test
    void editingWithoutAVersionIsRejected() throws Exception {
        String pin = pins + "/" + read(createIssue(worker, "Leak", 1, 1), "$.id");

        api.patch(worker, pin, "{\"title\":\"Leak 2\",\"x\":1,\"y\":1}").andExpect(status().isBadRequest());
    }

    @Test
    void lastPhotoOfAPhotoPinCannotBeDeleted() throws Exception {
        ResultActions created = createPhotoPin(worker, upload(worker)).andExpect(status().isCreated());
        String photo = pins + "/" + read(created, "$.id") + "/photos/" + read(created, "$.photos[0].id");

        api.delete(worker, photo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_REQUIRED"));
    }

    @Test
    void actionsOnADeletedPinAre404() throws Exception {
        String pin = assignedIssue();
        api.delete(owner, pin).andExpect(status().isNoContent());

        api.post(owner, pin + "/status", to("CLOSED")).andExpect(status().isNotFound());
        api.post(owner, pin + "/photos", "{\"keys\":" + json(upload(owner)) + ",\"phase\":\"REPORT\"}")
                .andExpect(status().isNotFound());
        api.delete(owner, pin + "/photos/" + UUID.randomUUID()).andExpect(status().isNotFound());
        api.get(owner, pin + "/audit").andExpect(status().isNotFound());
    }
}

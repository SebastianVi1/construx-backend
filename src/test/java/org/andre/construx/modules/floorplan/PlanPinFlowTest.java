package org.andre.construx.modules.floorplan;

import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anyone who can view a plan pins issues and photos on it; assigning and other people's pins take MANAGE_PLAN. */
class PlanPinFlowTest extends PlanPinTestBase {

    @Test
    void workerReportsAnIssueWithAPhoto() throws Exception {
        String key = upload(worker);

        createIssue(worker, "Cracked tile", 2, 2, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ISSUE"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.title").value("Cracked tile"))
                .andExpect(jsonPath("$.photoCount").value(1))
                .andExpect(jsonPath("$.coverUrl").value("memory://download/" + key))
                .andExpect(jsonPath("$.photos[0].phase").value("REPORT"))
                .andExpect(jsonPath("$.photos[0].url").value("memory://download/" + key))
                .andExpect(jsonPath("$.zoneId").value(zoneId.toString()))
                .andExpect(jsonPath("$.zoneName").value("Slab A"))
                .andExpect(jsonPath("$.createdBy.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.createdBy.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.assignee").value(nullValue()))
                .andExpect(jsonPath("$.can.edit").value(true))
                .andExpect(jsonPath("$.can.delete").value(true))
                .andExpect(jsonPath("$.can.assign").value(false))
                .andExpect(jsonPath("$.can.resolve").value(false))
                .andExpect(jsonPath("$.can.close").value(false));
    }

    @Test
    void anIssueNeedsNoPhoto() throws Exception {
        createIssue(worker, "Missing outlet cover", 1, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoCount").value(0))
                .andExpect(jsonPath("$.coverUrl").value(nullValue()));
    }

    @Test
    void pinOutsideEveryZoneHasNoZone() throws Exception {
        createIssue(worker, "Loose rail", 50, 50).andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").value(nullValue()))
                .andExpect(jsonPath("$.zoneName").value(nullValue()));
    }

    @Test
    void workerAddsAProgressPhoto() throws Exception {
        createPhotoPin(worker, upload(worker)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PHOTO"))
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.photoCount").value(1));
    }

    @Test
    void photoPinNeedsAPhoto() throws Exception {
        createPhotoPin(worker).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_REQUIRED"));
    }

    @Test
    void issueWithoutATitleIsRejected() throws Exception {
        createIssue(worker, null, 1, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_TITLE_REQUIRED"));
    }

    @Test
    void pinOffThePlanIsRejected() throws Exception {
        createIssue(worker, "Nowhere", -3, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_OUT_OF_BOUNDS"));
    }

    @Test
    void workerCannotAssign() throws Exception {
        api.post(worker, pins, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"T\",\"assigneeMembershipId\":\""
                + otherWorkerMembership + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void managerAssignsSiteStaff() throws Exception {
        api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"T\",\"assigneeMembershipId\":\""
                        + workerMembership + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.membershipId").value(workerMembership.toString()))
                .andExpect(jsonPath("$.assignee.fullName").value("Ana Perez"))
                .andExpect(jsonPath("$.can.assign").value(true))
                .andExpect(jsonPath("$.can.close").value(true));
    }

    @Test
    void assigneeWithoutSiteAccessIsRejected() throws Exception {
        Session outsider = api.signUp();
        UUID outsiderMembership = members.add(orgId, outsider.user(), "WORKER");

        api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"T\",\"assigneeMembershipId\":\""
                        + outsiderMembership + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_ASSIGNEE_NO_ACCESS"));
        api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"T\",\"assigneeMembershipId\":\""
                        + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_ASSIGNEE_NO_ACCESS"));
    }

    @Test
    void photoKeyFromAnotherPlanIsRejected() throws Exception {
        String otherPins = site + "/floor-plans/" + readyPlan(siteId, false) + "/pins";
        String foreignKey = upload(worker, otherPins);

        createIssue(worker, "T", 1, 1, foreignKey).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_INVALID"));
    }

    @Test
    void photoKeyNeverUploadedIsRejected() throws Exception {
        createIssue(worker, "T", 1, 1, requestUpload(worker, pins)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_INVALID"));
    }

    @Test
    void aPhotoCannotBeAttachedTwice() throws Exception {
        String key = upload(worker);
        createIssue(worker, "First", 1, 1, key).andExpect(status().isCreated());

        createIssue(worker, "Second", 1, 1, key).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_INVALID"));
    }

    @Test
    void unsupportedOrOversizedUploadsAreRejected() throws Exception {
        api.post(worker, pins + "/photo-uploads", "{\"contentType\":\"application/pdf\",\"sizeBytes\":10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_PHOTO_INVALID"));
        api.post(worker, pins + "/photo-uploads", "{\"contentType\":\"image/png\",\"sizeBytes\":5242881}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_PHOTO_INVALID"));
    }

    @Test
    void eleventhPhotoIsRejected() throws Exception {
        String[] keys = new String[11];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = upload(worker);
        }
        createIssue(worker, "T", 1, 1, keys).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIN_PHOTO_LIMIT"));
    }

    @Test
    void listFiltersByKindStatusAssigneeAndZone() throws Exception {
        createIssue(worker, "In the slab", 2, 2).andExpect(status().isCreated());
        api.post(owner, pins, "{\"kind\":\"ISSUE\",\"x\":60,\"y\":60,\"title\":\"For Ana\",\"assigneeMembershipId\":\""
                + workerMembership + "\"}").andExpect(status().isCreated());
        createPhotoPin(worker, upload(worker)).andExpect(status().isCreated());

        api.get(worker, pins).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
        api.get(worker, pins + "?kind=PHOTO").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("PHOTO"));
        api.get(worker, pins + "?status=OPEN").andExpect(jsonPath("$", hasSize(2)));
        api.get(worker, pins + "?status=CLOSED").andExpect(jsonPath("$", hasSize(0)));
        api.get(worker, pins + "?assignee=me").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("For Ana"))
                .andExpect(jsonPath("$[0].can.resolve").value(true));
        api.get(otherWorker, pins + "?assignee=me").andExpect(jsonPath("$", hasSize(0)));
        api.get(worker, pins + "?zoneId=" + zoneId).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void reporterEditsTheirOpenIssueButNotAnotherPersons() throws Exception {
        ResultActions created = createIssue(worker, "Tpyo", 1, 1).andExpect(status().isCreated());
        String pin = pins + "/" + read(created, "$.id");
        String body = "{\"title\":\"Typo\",\"description\":\"North wall\",\"x\":3,\"y\":3,\"version\":"
                + read(created, "$.version") + "}";

        api.patch(otherWorker, pin, body).andExpect(status().isForbidden());
        api.patch(worker, pin, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Typo"))
                .andExpect(jsonPath("$.description").value("North wall"))
                .andExpect(jsonPath("$.x").value(3.0));
        api.get(otherWorker, pin).andExpect(status().isOk())
                .andExpect(jsonPath("$.can.edit").value(false))
                .andExpect(jsonPath("$.can.delete").value(false));
    }

    @Test
    void reporterCannotReassignTheirOwnIssue() throws Exception {
        ResultActions created = createIssue(worker, "T", 1, 1).andExpect(status().isCreated());
        api.patch(worker, pins + "/" + read(created, "$.id"), "{\"title\":\"T\",\"x\":1,\"y\":1,"
                + "\"assigneeMembershipId\":\"" + workerMembership + "\",\"version\":" + read(created, "$.version") + "}")
                .andExpect(status().isForbidden());
    }

    @Test
    void staleVersionIsAConflict() throws Exception {
        ResultActions created = createIssue(worker, "T", 1, 1).andExpect(status().isCreated());
        String pin = pins + "/" + read(created, "$.id");
        String body = "{\"title\":\"T2\",\"x\":1,\"y\":1,\"version\":" + read(created, "$.version") + "}";
        api.patch(worker, pin, body).andExpect(status().isOk());

        api.patch(worker, pin, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_CONFLICT"));
    }

    @Test
    void deletedPinDisappears() throws Exception {
        String pin = pins + "/" + read(createIssue(worker, "T", 1, 1), "$.id");

        api.delete(otherWorker, pin).andExpect(status().isForbidden());
        api.delete(worker, pin).andExpect(status().isNoContent());
        api.get(worker, pin).andExpect(status().isNotFound());
        api.get(worker, pins).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void pinsNeedAReadyPlan() throws Exception {
        String created = api.post(owner, site + "/floor-plans/uploads",
                        "{\"name\":\"L2\",\"pageNumber\":1,\"contentType\":\"application/pdf\",\"sizeBytes\":1000}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String pending = site + "/floor-plans/" + com.jayway.jsonpath.JsonPath.read(created, "$.floorPlanId") + "/pins";

        api.post(owner, pending, "{\"kind\":\"ISSUE\",\"x\":1,\"y\":1,\"title\":\"T\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FLOOR_PLAN_NOT_READY"));
    }

    @Test
    void auditListsCreateAndUpdate() throws Exception {
        ResultActions created = createIssue(worker, "T", 1, 1).andExpect(status().isCreated());
        String pin = pins + "/" + read(created, "$.id");
        api.patch(worker, pin, "{\"title\":\"T2\",\"x\":1,\"y\":1,\"version\":" + read(created, "$.version") + "}")
                .andExpect(status().isOk());

        api.get(worker, pin + "/audit").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].action").value("UPDATE"))
                .andExpect(jsonPath("$[1].action").value("CREATE"));
    }
}

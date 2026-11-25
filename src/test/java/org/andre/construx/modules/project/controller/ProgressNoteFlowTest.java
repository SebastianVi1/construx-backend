package org.andre.construx.modules.project.controller;

import org.andre.construx.modules.identity.model.UserProfile;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserProfileRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.access.SiteScopes;
import org.andre.construx.modules.project.model.UpdateSource;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.modules.project.service.ProgressNoteService;
import org.andre.construx.shared.audit.AuditEvent;
import org.andre.construx.shared.audit.AuditEventRepository;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProgressNoteFlowTest.Storage.class)
class ProgressNoteFlowTest {
    private static final String GOAL_START = "2027-01-01T00:00:00Z";

    @TestConfiguration
    static class Storage {
        @Bean @Primary ObjectStorage inMemoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired TokenService tokens;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired PlatformTransactionManager txm;
    @Autowired AuditEventRepository auditEvents;
    @Autowired SiteScopes siteScopes;
    @Autowired ProgressNoteService progressNotes;
    @Autowired ObjectStorage storage;

    private Api api;
    private Members members;
    private Session owner, ana, bob;
    private UUID orgId, projectId, goalId, anaMembership, anaAccess;
    private String org, tasks, anaTask, anaTaskId;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        owner = api.signUp();
        orgId = api.createOrganization(owner, "Acme " + UUID.randomUUID());
        org = "/api/organizations/" + orgId;
        projectId = UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"Tower\"}")));
        goalId = UUID.fromString(Api.id(api.post(owner, org + "/projects/" + projectId + "/goals",
                "{\"name\":\"Phase\",\"startAt\":\"" + GOAL_START + "\",\"dueAt\":\"2027-03-01T00:00:00Z\"}")));
        tasks = org + "/projects/" + projectId + "/goals/" + goalId + "/activities";

        ana = api.signUp();
        profiles.save(new UserProfile(ana.user(), "Ana Perez", "+5215512345678"));
        anaMembership = members.add(orgId, ana.user(), "WORKER");
        anaAccess = members.grantSite(projectId, anaMembership);
        bob = api.signUp();
        members.grantSite(projectId, members.add(orgId, bob.user(), "WORKER"));
        anaTaskId = Api.id(api.post(owner, tasks, "{\"name\":\"Pour\",\"startAt\":\"" + GOAL_START
                + "\",\"dueAt\":\"2027-02-01T18:00:00Z\",\"assignees\":[{\"accessId\":\"" + anaAccess
                + "\",\"taskRole\":\"LEAD\"}]}").andExpect(status().isCreated()));
        anaTask = tasks + "/" + anaTaskId;
    }

    @Test
    void aTeamMemberWritesANoteAndTheTaskListsIt() throws Exception {
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"  Slab poured on the north side  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.text").value("Slab poured on the north side"))
                .andExpect(jsonPath("$.source").value("APP"))
                .andExpect(jsonPath("$.author.membershipId").value(anaMembership.toString()))
                .andExpect(jsonPath("$.author.name").value("Ana Perez"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        api.get(owner, anaTask + "/progress-notes").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("Slab poured on the north side"))
                .andExpect(jsonPath("$[0].source").value("APP"))
                .andExpect(jsonPath("$[0].author.name").value("Ana Perez"));
    }

    @Test
    void aWorkerOutsideTheTeamReadsButCannotWrite() throws Exception {
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Rebar placed\"}").andExpect(status().isCreated());

        api.get(bob, anaTask + "/progress-notes").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.post(bob, anaTask + "/progress-notes", "{\"text\":\"Not my task\"}").andExpect(status().isForbidden());

        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        api.get(finance, anaTask + "/progress-notes").andExpect(status().isOk());
        api.post(finance, anaTask + "/progress-notes", "{\"text\":\"Read only\"}").andExpect(status().isForbidden());
        api.get(owner, anaTask + "/progress-notes").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aTaskOutsideTheSiteOrCompanyIsNotFound() throws Exception {
        String otherSite = Api.id(api.post(owner, org + "/projects", "{\"name\":\"Other\"}"));
        String foreign = org + "/projects/" + otherSite + "/goals/" + goalId + "/activities/" + anaTaskId
                + "/progress-notes";
        api.get(owner, foreign).andExpect(status().isNotFound());
        api.post(owner, foreign, "{\"text\":\"Wrong site\"}").andExpect(status().isNotFound());

        Session stranger = api.signUp();
        api.get(stranger, anaTask + "/progress-notes").andExpect(status().isNotFound());
        api.post(stranger, anaTask + "/progress-notes", "{\"text\":\"Hi\"}").andExpect(status().isNotFound());
    }

    @Test
    void anEmptyNoteIsRejected() throws Exception {
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"   \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROGRESS_NOTE_EMPTY"));
        api.post(ana, anaTask + "/progress-notes", "{}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROGRESS_NOTE_EMPTY"));
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"" + "x".repeat(1000) + "\"}")
                .andExpect(status().isCreated());
        api.get(owner, anaTask + "/progress-notes").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void notesAreListedNewestFirstAndCanNeverBeChanged() throws Exception {
        String first = Api.id(api.post(ana, anaTask + "/progress-notes", "{\"text\":\"First\"}"));
        api.post(owner, anaTask + "/progress-notes", "{\"text\":\"Second\"}").andExpect(status().isCreated());

        api.get(ana, anaTask + "/progress-notes")
                .andExpect(jsonPath("$[0].text").value("Second"))
                .andExpect(jsonPath("$[1].text").value("First"));

        api.put(ana, anaTask + "/progress-notes/" + first, "{\"text\":\"Edited\"}")
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        api.delete(ana, anaTask + "/progress-notes/" + first)
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400));
        api.put(owner, anaTask + "/progress-notes", "{\"text\":\"Edited\"}")
                .andExpect(status().isMethodNotAllowed());
        api.delete(owner, anaTask + "/progress-notes").andExpect(status().isMethodNotAllowed());
        api.get(ana, anaTask + "/progress-notes")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].text").value("First"));
    }

    @Test
    void creatingANoteIsAuditedWithItsSource() throws Exception {
        String id = Api.id(api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Formwork up\"}"));

        List<AuditEvent> events = auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("PROGRESS_NOTE",
                UUID.fromString(id));
        assertEquals(1, events.size());
        assertEquals("CREATE", events.get(0).getAction());
        assertEquals(anaMembership, events.get(0).getActorMembershipId());
        assertTrue(events.get(0).getAfterState().contains("\"source\":\"APP\""));
        assertTrue(events.get(0).getAfterState().contains("Formwork up"));
    }

    @Test
    void aWhatsAppNoteGoesThroughTheSamePermissionsAndKeepsItsSource() throws Exception {
        var added = progressNotes.add(siteScopes.site(ana.user().getId(), orgId, projectId), goalId,
                UUID.fromString(anaTaskId), "Columns done", UpdateSource.WHATSAPP);

        api.get(owner, anaTask + "/progress-notes")
                .andExpect(jsonPath("$[0].text").value("Columns done"))
                .andExpect(jsonPath("$[0].source").value("WHATSAPP"));
        assertTrue(auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("PROGRESS_NOTE", added.id())
                .get(0).getAfterState().contains("\"source\":\"WHATSAPP\""));
        assertThrows(AccessDeniedException.class, () -> progressNotes.add(
                siteScopes.site(bob.user().getId(), orgId, projectId), goalId, UUID.fromString(anaTaskId),
                "Not mine", UpdateSource.WHATSAPP));
    }

    @Test
    void deletingTheTaskTakesItsNotesWithIt() throws Exception {
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Started\"}").andExpect(status().isCreated());

        api.delete(owner, anaTask).andExpect(status().isNoContent());
        api.get(owner, anaTask + "/progress-notes").andExpect(status().isNotFound());
    }

    // ---- photos ----

    /** Requests a photo upload on {@code task} and stores image bytes there, as the browser would; returns the key. */
    private String upload(Session session, String task, String contentType) throws Exception {
        String body = api.post(session, task + "/progress-notes/photo-uploads",
                        "{\"contentType\":\"" + contentType + "\",\"sizeBytes\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.headers['Content-Type']").value(contentType))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String key = com.jayway.jsonpath.JsonPath.read(body, "$.key");
        storage.put(key, new byte[]{1, 2, 3}, contentType);
        return key;
    }

    private static String keys(String... keys) {
        return java.util.Arrays.stream(keys).map(k -> "\"" + k + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @Test
    void deletingTheTaskDeletesItsNotesPhotosFromStorage() throws Exception {
        String first = upload(ana, anaTask, "image/jpeg");
        String second = upload(ana, anaTask, "image/png");
        api.post(ana, anaTask + "/progress-notes", "{\"photoKeys\":" + keys(first, second) + "}")
                .andExpect(status().isCreated());

        api.delete(owner, anaTask).andExpect(status().isNoContent());

        assertTrue(storage.head(first).isEmpty());
        assertTrue(storage.head(second).isEmpty());
    }

    @Test
    void aNoteCanBeJustPhotosAndTheListLinksEachInOrder() throws Exception {
        String first = upload(ana, anaTask, "image/jpeg");
        String second = upload(ana, anaTask, "image/png");

        api.post(ana, anaTask + "/progress-notes", "{\"photoKeys\":" + keys(first, second) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value(nullValue()))
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.photos[0].id").isNotEmpty())
                .andExpect(jsonPath("$.photos[0].url").value("memory://download/" + first))
                .andExpect(jsonPath("$.photos[1].url").value("memory://download/" + second));

        api.get(bob, anaTask + "/progress-notes").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].photos.length()").value(2))
                .andExpect(jsonPath("$[0].photos[0].url").value("memory://download/" + first))
                .andExpect(jsonPath("$[0].photos[1].url").value("memory://download/" + second));
    }

    @Test
    void aNoteHoldsAtMostFivePhotosAlongsideItsText() throws Exception {
        String[] six = new String[6];
        for (int i = 0; i < six.length; i++) six[i] = upload(ana, anaTask, "image/jpeg");

        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Six\",\"photoKeys\":" + keys(six) + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROGRESS_NOTE_TOO_MANY_PHOTOS"));
        api.get(owner, anaTask + "/progress-notes").andExpect(jsonPath("$.length()").value(0));

        String[] five = java.util.Arrays.copyOf(six, 5);
        api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Five\",\"photoKeys\":" + keys(five) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Five"))
                .andExpect(jsonPath("$.photos.length()").value(5))
                .andExpect(jsonPath("$.photos[4].url").value("memory://download/" + six[4]));
    }

    @Test
    void onlyJpegAndPngUpToFiveMegabytesCanBeUploaded() throws Exception {
        for (String body : List.of("{\"contentType\":\"image/webp\",\"sizeBytes\":3}",
                "{\"contentType\":\"image/gif\",\"sizeBytes\":3}",
                "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + (5 * 1024 * 1024 + 1) + "}",
                "{\"contentType\":\"image/png\",\"sizeBytes\":0}")) {
            api.post(ana, anaTask + "/progress-notes/photo-uploads", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PROGRESS_NOTE_PHOTO_INVALID"));
        }
        api.post(ana, anaTask + "/progress-notes/photo-uploads",
                "{\"contentType\":\"IMAGE/PNG\",\"sizeBytes\":" + 5 * 1024 * 1024 + "}")
                .andExpect(status().isCreated());
    }

    @Test
    void uploadingTakesTheRightToWriteANote() throws Exception {
        String body = "{\"contentType\":\"image/jpeg\",\"sizeBytes\":3}";
        api.post(bob, anaTask + "/progress-notes/photo-uploads", body).andExpect(status().isForbidden());
        Session finance = api.signUp();
        members.add(orgId, finance.user(), "FINANCE");
        api.post(finance, anaTask + "/progress-notes/photo-uploads", body).andExpect(status().isForbidden());
        api.post(api.signUp(), anaTask + "/progress-notes/photo-uploads", body).andExpect(status().isNotFound());
        api.post(owner, anaTask + "/progress-notes/photo-uploads", body).andExpect(status().isCreated());
    }

    @Test
    void aPhotoCountsOnlyIfItWasUploadedForThisTaskAndIsNotUsedYet() throws Exception {
        String otherTask = Api.id(api.post(owner, tasks, "{\"name\":\"Other\",\"startAt\":\"" + GOAL_START
                + "\",\"dueAt\":\"2027-02-01T18:00:00Z\",\"assignees\":[{\"accessId\":\"" + anaAccess
                + "\",\"taskRole\":\"LEAD\"}]}").andExpect(status().isCreated()));
        String foreign = upload(owner, tasks + "/" + otherTask, "image/jpeg");
        String notUploaded = com.jayway.jsonpath.JsonPath.read(api.post(ana, anaTask + "/progress-notes/photo-uploads",
                "{\"contentType\":\"image/jpeg\",\"sizeBytes\":3}").andReturn().getResponse().getContentAsString(),
                "$.key");
        String wrongType = upload(ana, anaTask, "image/png");
        storage.put(wrongType, new byte[]{1}, "text/html");
        String used = upload(ana, anaTask, "image/jpeg");
        api.post(ana, anaTask + "/progress-notes", "{\"photoKeys\":" + keys(used) + "}")
                .andExpect(status().isCreated());

        for (String key : List.of(foreign, notUploaded, wrongType, used, "whatsapp/pending/5215512345678/x",
                "orgs/" + orgId + "/projects/" + projectId + "/activities/" + anaTaskId + "/progress-notes/../x")) {
            api.post(ana, anaTask + "/progress-notes", "{\"text\":\"Hi\",\"photoKeys\":" + keys(key) + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PROGRESS_NOTE_PHOTO_INVALID"));
        }
        api.get(owner, anaTask + "/progress-notes").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theBotAttachesPhotosStoredUnderTheTasksOwnKeysWithoutHttp() throws Exception {
        var site = siteScopes.site(ana.user().getId(), orgId, projectId);
        String key = progressNotes.newPhotoKey(site, UUID.fromString(anaTaskId));
        storage.put(key, new byte[]{1, 2}, "image/jpeg");

        var added = progressNotes.add(site, goalId, UUID.fromString(anaTaskId), null, List.of(key),
                UpdateSource.WHATSAPP);

        assertEquals(1, added.photos().size());
        api.get(owner, anaTask + "/progress-notes")
                .andExpect(jsonPath("$[0].text").value(nullValue()))
                .andExpect(jsonPath("$[0].source").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].photos[0].url").value("memory://download/" + key));
        AuditEvent created = auditEvents.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("PROGRESS_NOTE",
                added.id()).get(0);
        assertTrue(created.getAfterState().contains("\"photoCount\":1"));
    }

    @Test
    void theBotCannotAttachAPhotoStillPendingForSomeNumber() {
        String pending = "whatsapp/pending/5215512345678/" + UUID.randomUUID();
        storage.put(pending, new byte[]{1, 2}, "image/jpeg");
        var site = siteScopes.site(ana.user().getId(), orgId, projectId);

        RuleViolationException refused = assertThrows(RuleViolationException.class, () -> progressNotes.add(site,
                goalId, UUID.fromString(anaTaskId), null, List.of(pending), UpdateSource.WHATSAPP));

        assertEquals("PROGRESS_NOTE_PHOTO_INVALID", refused.getCode());
    }
}

package org.andre.construx.modules.messaging;

import com.jayway.jsonpath.JsonPath;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.Button;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.ListRow;
import org.andre.construx.modules.messaging.service.WhatsAppDataCleanup;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.support.Api;
import org.andre.construx.support.Api.Session;
import org.andre.construx.support.Members;
import org.andre.construx.support.MutableClock;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.storage.InMemoryObjectStorage;
import org.andre.construx.shared.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.newSender;
import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.sign;
import static org.andre.construx.modules.messaging.WhatsAppWebhookFlowTest.textMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starting and finishing a Task from WhatsApp (Field updates contract §5 steps A–C and G, §6): a Linked number talks
 * to the bot through signed webhook posts, and what the bot sent is read from the {@link InMemoryWhatsAppGateway}.
 * Rows and buttons are tapped by their visible title, as a person would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FieldUpdateFlowTest.Fakes.class)
class FieldUpdateFlowTest {
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    static final String PHASE_START = "2027-01-01T00:00:00Z";

    @TestConfiguration
    static class Fakes {
        @Bean @Primary InMemoryWhatsAppGateway inMemoryWhatsAppGateway() {
            return new InMemoryWhatsAppGateway();
        }

        @Bean @Primary MutableClock whatsAppClock() {
            return new MutableClock(NOW);
        }

        @Bean @Primary ObjectStorage inMemoryStorage() {
            return new InMemoryObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired TokenService tokens;
    @Autowired InMemoryWhatsAppGateway gateway;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager txm;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired ProjectRepository projects;
    @Autowired ProjectAccessRepository accesses;
    @Autowired AuditService audit;
    @Autowired ObjectStorage storage;
    @Autowired WhatsAppDataCleanup cleanup;

    Api api;
    Members members;
    Session owner;
    UUID orgId;
    String orgName;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, users, tokens);
        members = new Members(txm, organizations, memberships, projects, accesses);
        clock.set(NOW.toString());
        gateway.clear();
        owner = api.signUp();
        orgName = "Acme " + UUID.randomUUID();
        orgId = api.createOrganization(owner, orgName);
        org = "/api/organizations/" + orgId;
    }

    @Test
    void aWorkerWithOneSiteGetsStraightToTheOpenTasksOfTheirTeamSoonestDueFirst() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        task(site, phase, "Rebar", "2027-01-10T00:00:00Z", bob.access);
        String formwork = task(site, phase, "Formwork", "2027-01-20T00:00:00Z", bob.access, ana.access);
        api.post(ana.session, tasks(site, phase) + "/" + formwork + "/start", null).andExpect(status().isOk());
        String done = task(site, phase, "Survey", "2027-01-05T00:00:00Z", ana.access);
        api.post(ana.session, tasks(site, phase) + "/" + done + "/complete", null).andExpect(status().isOk());
        String waId = link(ana.session, "es");

        say(waId, "hola");

        InMemoryWhatsAppGateway.Sent list = last(waId);
        assertThat(list.body()).isEqualTo("Tower: ¿qué tarea?");
        assertThat(list.rows()).extracting(ListRow::title, ListRow::description).containsExactly(
                tuple("Formwork", "Structure · En curso"),
                tuple("Pour slab", "Structure · Por hacer"));
    }

    @Test
    void aSiteManagerSeesEveryOpenTaskOfTheSiteWithTheirOwnTeamsFirst() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker mia = worker(site, "SITE_MANAGER");
        Worker bob = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", bob.access);
        task(site, phase, "Inspect", "2027-03-01T00:00:00Z", mia.access);
        task(site, phase, "Rebar", "2027-01-10T00:00:00Z", bob.access);
        String waId = link(mia.session, "es");

        say(waId, "hola");

        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Inspect", "Rebar", "Pour slab");
    }

    @Test
    void aLongTaskListComesNineAtATimeWithSeeMore() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker mia = worker(site, "SITE_MANAGER");
        Worker bob = worker(site, "WORKER");
        for (int day = 10; day < 22; day++) {
            task(site, phase, "Task " + day, "2027-01-" + day + "T00:00:00Z", bob.access);
        }
        String waId = link(mia.session, "en");

        say(waId, "hi");
        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Task 10", "Task 11", "Task 12",
                "Task 13", "Task 14", "Task 15", "Task 16", "Task 17", "Task 18", "See more");

        tap(waId, "See more");
        assertThat(last(waId).body()).isEqualTo("Tower: which task?");
        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Task 19", "Task 20", "Task 21");
    }

    @Test
    void withSitesInTwoCompaniesTheListShowsOnlyAccessibleSitesWithTheirCompany() throws Exception {
        UUID tower = site("Tower");
        site("Hidden");
        Worker ana = worker(tower, "WORKER");
        task(tower, phase(tower, "Structure"), "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        Session other = api.signUp();
        String otherName = "Zeta " + UUID.randomUUID();
        UUID otherOrg = api.createOrganization(other, otherName);
        UUID plaza = UUID.fromString(Api.id(api.post(other, "/api/organizations/" + otherOrg + "/projects",
                "{\"name\":\"Plaza\"}")));
        members.grantSite(plaza, members.add(otherOrg, ana.session.user(), "WORKER"));
        String waId = link(ana.session, "es");

        say(waId, "hola");

        assertThat(last(waId).body()).isEqualTo("¿En qué obra?");
        assertThat(last(waId).rows()).extracting(ListRow::title, ListRow::description).containsExactly(
                tuple("Tower", orgName), tuple("Plaza", otherName));

        tap(waId, "Tower");
        assertThat(last(waId).body()).isEqualTo("Tower: ¿qué tarea?");
        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Pour slab");
    }

    @Test
    void startingATaskSavesItOnlyAfterYesAndOffersAnotherTask() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");

        say(waId, "hola");
        tap(waId, "Pour slab");
        assertThat(last(waId).body()).isEqualTo("Pour slab: ¿qué hiciste?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Iniciar", "Terminar", "Nota");

        tap(waId, "Iniciar");
        assertThat(last(waId).body()).isEqualTo("¿Quieres agregar fotos?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Sí", "No");
        tap(waId, "No");
        assertThat(last(waId).body()).isEqualTo("📋 Avance de campo · Tower\nPour slab → Iniciada\n\n¿Lo guardo?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Sí", "No");
        assertStatus(site, phase, pour, "TODO");

        tap(waId, "Sí");
        assertThat(last(waId).body()).isEqualTo("✅ Guardado en Construx.");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Otra tarea");
        assertStatus(site, phase, pour, "IN_PROGRESS");
        assertThat(audit.listForEntity("ACTIVITY", UUID.fromString(pour))).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("START");
            assertThat(event.afterState()).contains("\"source\":\"WHATSAPP\"");
        });

        tap(waId, "Otra tarea");
        assertThat(last(waId).body()).isEqualTo("Tower: ¿qué tarea?");
        assertThat(last(waId).rows()).extracting(ListRow::description).containsExactly("Structure · En curso");
    }

    @Test
    void aTaskInProgressCanOnlyBeFinishedAndTheBotSpeaksTheNumbersLanguage() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        api.post(ana.session, tasks(site, phase) + "/" + pour + "/start", null).andExpect(status().isOk());
        String waId = link(ana.session, "en");

        say(waId, "hi");
        assertThat(last(waId).rows()).extracting(ListRow::description).containsExactly("Structure · In progress");
        tap(waId, "Pour slab");
        assertThat(last(waId).body()).isEqualTo("Pour slab: what did you do?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Finish", "Note");
        tap(waId, "Finish");
        assertThat(last(waId).body()).isEqualTo("Want to add photos?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Yes", "No");
        tap(waId, "No");
        assertThat(last(waId).body()).isEqualTo("📋 Field update · Tower\nPour slab → Finished\n\nSave it?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Yes", "No");
        tap(waId, "Yes");

        assertThat(last(waId).body()).isEqualTo("✅ Saved to Construx.");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Another task");
        api.get(ana.session, tasks(site, phase) + "/" + pour)
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
        assertThat(audit.listForEntity("ACTIVITY", UUID.fromString(pour)).getFirst()).satisfies(event -> {
            assertThat(event.action()).isEqualTo("COMPLETE");
            assertThat(event.afterState()).contains("\"source\":\"WHATSAPP\"");
        });
    }

    @Test
    void noteAsksForTheTextAndTheSummaryShowsIt() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");

        tap(waId, "Nota");
        assertThat(last(waId).body()).isEqualTo("Escribe la nota (máx. 1000 caracteres).");
        say(waId, "  Slab poured on the east side  ");
        assertThat(last(waId).body()).isEqualTo("¿Quieres agregar fotos?");
        tap(waId, "No");

        assertThat(last(waId).body()).isEqualTo("📋 Avance de campo · Tower\nPour slab → Nota\n"
                + "Nota: \"Slab poured on the east side\"\n\n¿Lo guardo?");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Sí", "No");
    }

    @Test
    void yesSavesTheNoteFromWhatsAppByTheAuthorAndLeavesTheTaskAsItWas() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        api.post(ana.session, tasks(site, phase) + "/" + pour + "/start", null).andExpect(status().isOk());
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Note");
        say(waId, "Slab poured on the east side");
        tap(waId, "No");

        tap(waId, "Yes");

        assertThat(last(waId).body()).isEqualTo("✅ Saved to Construx.");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Another task");
        api.get(owner, tasks(site, phase) + "/" + pour + "/progress-notes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("Slab poured on the east side"))
                .andExpect(jsonPath("$[0].source").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].author.membershipId").value(ana.membership.toString()))
                .andExpect(jsonPath("$[0].photos.length()").value(0));
        assertStatus(site, phase, pour, "IN_PROGRESS");
    }

    @Test
    void aNoteOverAThousandCharactersIsAskedForAgainAndAFullOneIsShortenedOnlyInTheSummary() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Note");

        say(waId, "x".repeat(1001));
        assertThat(last(waId).body()).isEqualTo("The note is too long. Keep it to 1000 characters.");
        assertThat(last(waId).buttons()).isEmpty();

        say(waId, "y".repeat(1000));
        tap(waId, "No");
        // WhatsApp takes at most 1,024 characters in the text above buttons.
        assertThat(last(waId).body()).hasSize(1024)
                .startsWith("📋 Field update · Tower\nPour slab → Note\nNote: \"yyyy")
                .endsWith("yyy…\"\n\nSave it?");

        tap(waId, "Yes");
        api.get(owner, tasks(site, phase) + "/" + pour + "/progress-notes")
                .andExpect(jsonPath("$[0].text").value("y".repeat(1000)));
    }

    @Test
    void aTapWhileTheNoteIsAwaitedGetsUseButtonsAndTheQuestionAgain() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        InMemoryWhatsAppGateway.Sent actions = last(waId);
        tap(waId, "Nota");
        gateway.clear();

        tapId(waId, actions.buttons().getFirst().id(), "button_reply");
        sendAudio(waId);

        assertThat(bodies(waId)).containsExactly("Por ahora usa los botones.",
                "Escribe la nota (máx. 1000 caracteres).", "Por ahora usa los botones.",
                "Escribe la nota (máx. 1000 caracteres).");
    }

    @Test
    void aNoteShapedLikeALinkCodeThatIsNoValidCodeIsTakenAsTheNote() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");

        say(waId, "CX-0000");
        tap(waId, "No");

        assertThat(last(waId).body()).isEqualTo("📋 Avance de campo · Tower\nPour slab → Nota\n"
                + "Nota: \"CX-0000\"\n\n¿Lo guardo?");
    }

    @Test
    void aValidCodeWhileTheNoteIsAwaitedStillMovesTheNumber() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        task(site, phase, "Rebar", "2027-01-10T00:00:00Z", bob.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");

        relink(bob.session, waId);
        assertThat(last(waId).body()).startsWith("¡Listo, ");
        say(waId, "hola");

        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Rebar");
        assertNoNotes(site, phase, pour);
    }

    @Test
    void noAfterANoteSavesNothing() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");
        say(waId, "Falta cemento");
        tap(waId, "No");

        tap(waId, "No");

        assertThat(last(waId).body()).isEqualTo("Cancelado. Escribe de nuevo cuando quieras.");
        assertNoNotes(site, phase, pour);
    }

    @Test
    void aNoteOnATaskFinishedBeforeYesGetsChangedAndIsNotSaved() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", bob.access, ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");
        say(waId, "Falta cemento");
        tap(waId, "No");

        api.post(bob.session, tasks(site, phase) + "/" + pour + "/complete", null).andExpect(status().isOk());
        tap(waId, "Sí");

        assertThat(last(waId).body()).isEqualTo("Esta tarea cambió mientras tanto. Empieza de nuevo.");
        assertNoNotes(site, phase, pour);
    }

    @Test
    void noCancelsSavesNothingAndTheNextMessageStartsOver() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Terminar");
        tap(waId, "No");
        InMemoryWhatsAppGateway.Sent summary = last(waId);

        tap(waId, "No");

        assertThat(last(waId).body()).isEqualTo("Cancelado. Escribe de nuevo cuando quieras.");
        assertStatus(site, phase, pour, "TODO");
        assertThat(audit.listForEntity("ACTIVITY", UUID.fromString(pour))).isEmpty();
        tapId(waId, summary.buttons().getFirst().id(), "button_reply");
        assertStatus(site, phase, pour, "TODO");
        assertThat(last(waId).body()).isEqualTo("Tower: ¿qué tarea?");
    }

    @Test
    void aTeammateFinishingTheTaskBeforeYesGetsChangedAndNothingIsSaved() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", bob.access, ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Terminar");
        tap(waId, "No");

        api.post(bob.session, tasks(site, phase) + "/" + pour + "/complete", null).andExpect(status().isOk());
        tap(waId, "Sí");

        assertThat(last(waId).body()).isEqualTo("Esta tarea cambió mientras tanto. Empieza de nuevo.");
        assertThat(audit.listForEntity("ACTIVITY", UUID.fromString(pour))).singleElement()
                .satisfies(event -> assertThat(event.afterState()).contains("\"source\":\"APP\""));
    }

    @Test
    void aMemberRemovedBeforeYesGetsChangedAndNoLongerSeesTheSite() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "No");

        api.delete(owner, org + "/members/" + ana.membership).andExpect(status().isNoContent());
        tap(waId, "Sí");

        assertThat(last(waId).body()).isEqualTo("Esta tarea cambió mientras tanto. Empieza de nuevo.");
        assertStatus(site, phase, pour, "TODO");
        say(waId, "hola");
        assertThat(last(waId).body()).isEqualTo("No tienes obras asignadas.");
    }

    @Test
    void aTaskOutsideTheWorkersTeamCannotBeChosenEvenWithItsId() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String rebar = task(site, phase, "Rebar", "2027-01-10T00:00:00Z", bob.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");

        tapId(waId, "task:" + phase + ":" + rebar, "list_reply");

        assertThat(last(waId).body()).isEqualTo("Esta tarea cambió mientras tanto. Empieza de nuevo.");
        assertStatus(site, phase, rebar, "TODO");
    }

    @Test
    void audioDuringTheTaskListGetsUseButtonsAndTheSameListAgain() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        InMemoryWhatsAppGateway.Sent list = last(waId);
        gateway.clear();

        sendAudio(waId);

        assertThat(gateway.sentTo(waId)).hasSize(2);
        assertThat(gateway.sentTo(waId).getFirst().body()).isEqualTo("Por ahora usa los botones.");
        assertThat(last(waId)).isEqualTo(list);
    }

    @Test
    void textWhileTheSummaryWaitsGetsUseButtonsAndTheSummaryAgain() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "No");
        InMemoryWhatsAppGateway.Sent summary = last(waId);
        gateway.clear();

        say(waId, "sí");

        assertThat(bodies(waId)).containsExactly("Por ahora usa los botones.", summary.body());
        assertStatus(site, phase, pour, "TODO");
    }

    @Test
    void withoutSitesOrOpenTasksTheBotSaysSo() throws Exception {
        Session loner = api.signUp();
        String lonely = link(loner, "es");
        say(lonely, "hola");
        assertThat(bodies(lonely)).containsExactly("No tienes obras asignadas.");

        UUID site = site("Tower");
        Worker ana = worker(site, "WORKER");
        String waId = link(ana.session, "en");
        say(waId, "hi");
        assertThat(bodies(waId)).containsExactly("You have no open tasks on Tower.");
    }

    @Test
    void stopInTheMiddleEndsTheSessionSoAnOldYesDoesNothing() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "No");
        String yes = last(waId).buttons().getFirst().id();

        say(waId, "BAJA");
        relink(ana.session, waId);
        tapId(waId, yes, "button_reply");

        assertStatus(site, phase, pour, "TODO");
        assertThat(last(waId).body()).isEqualTo("Tower: ¿qué tarea?");
    }

    @Test
    void aNumberMovingToAnotherAccountLeavesThePreviousOwnersSessionBehind() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        Worker bob = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        task(site, phase, "Rebar", "2027-01-10T00:00:00Z", bob.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "No");
        String yes = last(waId).buttons().getFirst().id();

        relink(bob.session, waId);
        tapId(waId, yes, "button_reply");

        assertStatus(site, phase, pour, "TODO");
        assertThat(last(waId).rows()).extracting(ListRow::title).containsExactly("Rebar");
    }

    @Test
    void unlinkingFromTheProfileEndsTheSession() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "No");
        String yes = last(waId).buttons().getFirst().id();

        api.delete(ana.session, "/api/profile/whatsapp").andExpect(status().isNoContent());
        relink(ana.session, waId);
        tapId(waId, yes, "button_reply");

        assertStatus(site, phase, pour, "TODO");
        assertThat(last(waId).body()).isEqualTo("Tower: ¿qué tarea?");
    }

    @Test
    void finishingWithTwoPhotosFinishesTheTaskAndAddsAPhotoOnlyNote() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Finish");

        tap(waId, "Yes");
        assertThat(last(waId).body()).isEqualTo("Send up to 5 photos, then tap Done.");
        assertThat(last(waId).buttons()).extracting(Button::title).containsExactly("Done");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/png");
        assertThat(pendingObjects(waId)).hasSize(2);
        tap(waId, "Done");
        assertThat(last(waId).body()).isEqualTo("📋 Field update · Tower\nPour slab → Finished\n2 photos\n\nSave it?");

        tap(waId, "Yes");

        assertThat(last(waId).body()).isEqualTo("✅ Saved to Construx.");
        assertStatus(site, phase, pour, "DONE");
        api.get(owner, tasks(site, phase) + "/" + pour + "/progress-notes")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").doesNotExist())
                .andExpect(jsonPath("$[0].source").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].photos.length()").value(2));
        // The photos moved to the note's own keys; nothing stays pending.
        assertThat(pendingObjects(waId)).isEmpty();
        assertThat(((InMemoryObjectStorage) storage).keys(noteKeys(site, pour))).hasSize(2);
    }

    @Test
    void aPhotoTheNoteRefusesAtYesGetsChangedAndSavesNothing() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Finish");
        tap(waId, "Yes");
        sendImage(waId, "image/jpeg");
        tap(waId, "Done");
        // What storage holds is no longer a photo by the time of YES.
        storage.put(pendingObjects(waId).iterator().next(), new byte[]{1}, "text/html");

        tap(waId, "Yes");

        assertThat(last(waId).body()).isEqualTo("This task changed in the meantime. Start again.");
        assertStatus(site, phase, pour, "TODO");
        assertNoNotes(site, phase, pour);
        assertThat(pendingObjects(waId)).isEmpty();
        assertThat(((InMemoryObjectStorage) storage).keys(noteKeys(site, pour))).isEmpty();
    }

    @Test
    void aNoteWithThreePhotosThenNoLeavesNoNoteAndNoStoredPhotos() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");
        say(waId, "Falta cemento");
        tap(waId, "Sí");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/png");
        assertThat(pendingObjects(waId)).hasSize(3);
        tap(waId, "Listo");
        assertThat(last(waId).body()).isEqualTo("📋 Avance de campo · Tower\nPour slab → Nota\n"
                + "Nota: \"Falta cemento\"\n3 fotos\n\n¿Lo guardo?");

        tap(waId, "No");

        assertThat(last(waId).body()).isEqualTo("Cancelado. Escribe de nuevo cuando quieras.");
        assertNoNotes(site, phase, pour);
        assertThat(pendingObjects(waId)).isEmpty();
    }

    @Test
    void aNoteWithThreePhotosThenYesSavesTheTextAndThePhotosInOrder() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Note");
        say(waId, "Slab poured");
        tap(waId, "Yes");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/png");
        sendImage(waId, "image/jpeg");
        tap(waId, "Done");

        tap(waId, "Yes");

        api.get(owner, tasks(site, phase) + "/" + pour + "/progress-notes")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("Slab poured"))
                .andExpect(jsonPath("$[0].source").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].photos.length()").value(3));
        assertStatus(site, phase, pour, "TODO");
    }

    @Test
    void theSixthPhotoGetsPhotoLimitAndIsNotKept() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Start");
        tap(waId, "Yes");
        for (int i = 0; i < 5; i++) {
            sendImage(waId, "image/jpeg");
        }
        gateway.clear();

        sendImage(waId, "image/jpeg");

        assertThat(bodies(waId)).containsExactly("You already have 5 photos. Tap Done to continue.");
        assertThat(pendingObjects(waId)).hasSize(5);
        tap(waId, "Done");
        assertThat(last(waId).body()).isEqualTo("📋 Field update · Tower\nPour slab → Started\n5 photos\n\nSave it?");
        tap(waId, "Yes");
        assertStatus(site, phase, pour, "IN_PROGRESS");
        api.get(owner, tasks(site, phase) + "/" + pour + "/progress-notes")
                .andExpect(jsonPath("$[0].photos.length()").value(5));
    }

    @Test
    void imagesOutsideThePhotoStepGetUseButtonsAndAreNotStored() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Terminar");
        gateway.clear();

        sendImage(waId, "image/jpeg");
        tap(waId, "No");
        sendImage(waId, "image/jpeg");

        assertThat(bodies(waId)).containsExactly("Por ahora usa los botones.", "¿Quieres agregar fotos?",
                "📋 Avance de campo · Tower\nPour slab → Terminada\n\n¿Lo guardo?", "Por ahora usa los botones.",
                "📋 Avance de campo · Tower\nPour slab → Terminada\n\n¿Lo guardo?");
        assertThat(pendingObjects(waId)).isEmpty();
    }

    @Test
    void doneWithoutPhotosIsTheSameAsNoAndSavesNoNote() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Finish");
        tap(waId, "Yes");

        tap(waId, "Done");
        assertThat(last(waId).body()).isEqualTo("📋 Field update · Tower\nPour slab → Finished\n\nSave it?");
        tap(waId, "Yes");

        assertStatus(site, phase, pour, "DONE");
        assertNoNotes(site, phase, pour);
    }

    @Test
    void anImageThatIsNotJpegOrPngGetsUseButtonsAndTheRequestAgain() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Start");
        tap(waId, "Yes");
        gateway.clear();

        sendImage(waId, "image/webp");
        say(waId, "done");

        assertThat(bodies(waId)).containsExactly("Please use the buttons for now.",
                "Send up to 5 photos, then tap Done.", "Please use the buttons for now.",
                "Send up to 5 photos, then tap Done.");
        assertThat(pendingObjects(waId)).isEmpty();
    }

    @Test
    void stopWhilePhotosArePendingDeletesThem() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "Sí");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/jpeg");

        say(waId, "BAJA");

        assertThat(pendingObjects(waId)).isEmpty();
    }

    @Test
    void aFullNoteWithPhotosStillFitsTheSummary() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        tap(waId, "Note");
        say(waId, "y".repeat(1000));
        tap(waId, "Yes");
        sendImage(waId, "image/png");

        tap(waId, "Done");

        assertThat(last(waId).body()).hasSize(1024).endsWith("yyy…\"\n1 photo\n\nSave it?");
    }

    // --- idle expiry and cleanup (contract §5 rule 4, §2 cleanup, §8 case 11) -----------------------------------

    @Test
    void anOldTapAfterThirtyOneMinutesIdleGetsExpiredANewMenuAndThePhotosAreGone() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        String pour = task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Iniciar");
        tap(waId, "Sí");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/png");
        tap(waId, "Listo");
        InMemoryWhatsAppGateway.Sent summary = last(waId);
        gateway.clear();

        idle(31);
        tapId(waId, summary.buttons().getFirst().id(), "button_reply");

        assertThat(bodies(waId)).containsExactly("Pasó mucho tiempo, empecemos de nuevo.", "Tower: ¿qué tarea?");
        assertStatus(site, phase, pour, "TODO");
        assertNoNotes(site, phase, pour);
        assertThat(pendingObjects(waId)).isEmpty();
    }

    @Test
    void anyMessageAfterThirtyMinutesIdleStartsOverWithoutExpired() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "en");
        say(waId, "hi");
        tap(waId, "Pour slab");
        idle(29);
        tap(waId, "Start");
        assertThat(last(waId).body()).isEqualTo("Want to add photos?");
        gateway.clear();

        idle(30);
        say(waId, "hi");

        assertThat(bodies(waId)).containsExactly("Tower: which task?");
    }

    @Test
    void aCodeShapedTextAfterTheNoteWentIdleIsTakenAsACode() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Nota");
        gateway.clear();

        idle(31);
        say(waId, "CX-0000");

        assertThat(bodies(waId)).singleElement().asString()
                .startsWith("Ese código no es válido o ya venció. Genera uno nuevo en tu perfil.");
    }

    @Test
    void theCleanupEndsSessionsIdleThirtyMinutesWithTheirPendingPhotos() throws Exception {
        UUID site = site("Tower");
        UUID phase = phase(site, "Structure");
        Worker ana = worker(site, "WORKER");
        task(site, phase, "Pour slab", "2027-02-01T00:00:00Z", ana.access);
        String waId = link(ana.session, "es");
        say(waId, "hola");
        tap(waId, "Pour slab");
        tap(waId, "Terminar");
        tap(waId, "Sí");
        sendImage(waId, "image/jpeg");
        sendImage(waId, "image/jpeg");
        InMemoryWhatsAppGateway.Sent photoStep = last(waId);

        idle(29);
        cleanup.purge();
        assertThat(pendingObjects(waId)).hasSize(2);

        idle(1);
        cleanup.purge();
        assertThat(pendingObjects(waId)).isEmpty();
        gateway.clear();
        tapId(waId, photoStep.buttons().getFirst().id(), "button_reply");
        assertThat(bodies(waId)).containsExactly("Pasó mucho tiempo, empecemos de nuevo.", "Tower: ¿qué tarea?");
    }

    // --- fixture -------------------------------------------------------------------------------------------------

    /** Moves the clock on by this many minutes without a message. */
    void idle(long minutes) {
        clock.set(clock.instant().plus(minutes, ChronoUnit.MINUTES).toString());
    }

    record Worker(Session session, UUID membership, UUID access) {}

    UUID site(String name) throws Exception {
        return UUID.fromString(Api.id(api.post(owner, org + "/projects", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated())));
    }

    UUID phase(UUID site, String name) throws Exception {
        return UUID.fromString(Api.id(api.post(owner, org + "/projects/" + site + "/goals", "{\"name\":\"" + name
                + "\",\"startAt\":\"" + PHASE_START + "\",\"dueAt\":\"2027-06-01T00:00:00Z\"}")
                .andExpect(status().isCreated())));
    }

    String tasks(UUID site, UUID phase) {
        return org + "/projects/" + site + "/goals/" + phase + "/activities";
    }

    /** A Task whose Lead is the first access and whose crew is the rest; returns its id. */
    String task(UUID site, UUID phase, String name, String dueAt, UUID lead, UUID... crew) throws Exception {
        StringBuilder team = new StringBuilder("{\"accessId\":\"" + lead + "\",\"taskRole\":\"LEAD\"}");
        for (UUID member : crew) {
            team.append(",{\"accessId\":\"").append(member).append("\",\"taskRole\":\"CREW\"}");
        }
        return Api.id(api.post(owner, tasks(site, phase), "{\"name\":\"" + name + "\",\"startAt\":\"" + PHASE_START
                + "\",\"dueAt\":\"" + dueAt + "\",\"assignees\":[" + team + "]}").andExpect(status().isCreated()));
    }

    /** A new person in the company with the role and access to the Site. */
    Worker worker(UUID site, String roleCode) throws Exception {
        Session session = api.signUp();
        UUID membership = members.add(orgId, session.user(), roleCode);
        return new Worker(session, membership, members.grantSite(site, membership));
    }

    /** Links a new number to the user in the language; what the bot said so far is forgotten. */
    String link(Session user, String language) throws Exception {
        String waId = newSender();
        say(waId, code(user, language));
        gateway.clear();
        return waId;
    }

    /** Links the number to the user again, in Spanish. */
    void relink(Session user, String waId) throws Exception {
        say(waId, code(user, "es"));
    }

    String code(Session user, String language) throws Exception {
        String body = api.post(user, "/api/profile/whatsapp/link-code", "{\"language\":\"" + language + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    void assertStatus(UUID site, UUID phase, String task, String expected) throws Exception {
        api.get(owner, tasks(site, phase) + "/" + task).andExpect(jsonPath("$.status").value(expected));
    }

    void assertNoNotes(UUID site, UUID phase, String task) throws Exception {
        api.get(owner, tasks(site, phase) + "/" + task + "/progress-notes")
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    void say(String waId, String text) throws Exception {
        receive(textMessage(waId, "wamid." + UUID.randomUUID(), text));
    }

    /** Taps the row or button with this title in the last message the number got. */
    void tap(String waId, String title) throws Exception {
        InMemoryWhatsAppGateway.Sent sent = last(waId);
        String id = sent.rows().stream().filter(row -> row.title().equals(title)).map(ListRow::id).findFirst()
                .or(() -> sent.buttons().stream().filter(b -> b.title().equals(title)).map(Button::id).findFirst())
                .orElseThrow(() -> new AssertionError("No row or button " + title + " in " + sent));
        tapId(waId, id, sent.rows().isEmpty() ? "button_reply" : "list_reply");
    }

    void tapId(String waId, String id, String type) throws Exception {
        receive(envelope(waId, """
                "type": "interactive", "interactive": {"type": "%1$s", "%1$s": {"id": "%2$s", "title": "x"}}
                """.formatted(type, id)));
    }

    void sendAudio(String waId) throws Exception {
        receive(envelope(waId, """
                "type": "audio", "audio": {"id": "media-1", "mime_type": "audio/ogg"}
                """));
    }

    /** An image the number sends: the gateway serves its bytes under a new media id. */
    void sendImage(String waId, String mimeType) throws Exception {
        String mediaId = "media-" + UUID.randomUUID();
        gateway.addMedia(mediaId, ("bytes of " + mediaId).getBytes(), mimeType);
        receive(envelope(waId, """
                "type": "image", "image": {"id": "%s", "mime_type": "%s"}
                """.formatted(mediaId, mimeType)));
    }

    /** Where the photos of the Task's Progress notes are stored. */
    String noteKeys(UUID site, String task) {
        return "orgs/" + orgId + "/projects/" + site + "/activities/" + task + "/progress-notes/";
    }

    /** What storage holds for the number's photos received before YES. */
    Set<String> pendingObjects(String waId) {
        return ((InMemoryObjectStorage) storage).keys("whatsapp/pending/" + waId + "/");
    }

    InMemoryWhatsAppGateway.Sent last(String waId) {
        List<InMemoryWhatsAppGateway.Sent> sent = gateway.sentTo(waId);
        assertThat(sent).isNotEmpty();
        return sent.getLast();
    }

    List<String> bodies(String waId) {
        return gateway.sentTo(waId).stream().map(InMemoryWhatsAppGateway.Sent::body).toList();
    }

    private static String envelope(String waId, String message) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "102290129340398", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "15550000001", "phone_number_id": "1000000001"},
                   "contacts": [{"profile": {"name": "Juan"}, "wa_id": "%1$s"}],
                   "messages": [{"from": "%1$s", "id": "wamid.%2$s", "timestamp": "1773154800", %3$s}]
                 }}]}]}
                """.formatted(waId, UUID.randomUUID(), message);
    }

    private void receive(String body) throws Exception {
        mvc.perform(post("/api/webhooks/whatsapp").contentType("application/json").content(body)
                        .header("X-Hub-Signature-256", sign(body)))
                .andExpect(status().isOk());
    }
}

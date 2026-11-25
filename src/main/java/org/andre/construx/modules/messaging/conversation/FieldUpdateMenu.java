package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.Button;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.ListRow;
import org.andre.construx.modules.messaging.inbound.InboundMessage;
import org.andre.construx.modules.messaging.model.ConversationSession;
import org.andre.construx.modules.messaging.model.FieldAction;
import org.andre.construx.modules.messaging.model.Language;
import org.andre.construx.modules.messaging.model.SessionStep;
import org.andre.construx.modules.messaging.service.ConversationSessions;
import org.andre.construx.modules.messaging.service.FieldUpdateService;
import org.andre.construx.modules.messaging.service.FieldUpdateService.Changed;
import org.andre.construx.modules.messaging.service.FieldUpdateService.SiteChoice;
import org.andre.construx.modules.messaging.service.FieldUpdateService.SiteTasks;
import org.andre.construx.modules.messaging.service.FieldUpdateService.TaskChoice;
import org.andre.construx.modules.messaging.service.PendingPhotos;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.ProgressNote;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The menu conversation of a Linked number (contract §5 rules 4–5, steps A–G): Site → Task → action (→ note
 * text) → photos? (→ images, Done) → summary → YES / NO. Each step has an {@code accept} (is this the input the step waits for, and what it leads to) and a
 * {@code show} (the step's menu, sent on entering it and again after {@code USE_BUTTONS}). A new step is a
 * {@link SessionStep} plus one case in {@link #accept} and one in
 * {@link #show}; a new button is a reply id prefix below.
 *
 * <p>Nothing is trusted from a tapped row or button: every choice is looked up again among what the user may reach
 * now, and a choice that is no longer there ends the conversation with {@code CHANGED}.
 */
@Component
public class FieldUpdateMenu {
    /** Rows per list page when there are more than fit; the 10th row is "See more". */
    static final int PAGE_SIZE = 9;
    private static final int MAX_ROWS = 10;
    private static final int ROW_TITLE_MAX = 24;
    private static final int ROW_DESCRIPTION_MAX = 72;
    /** The most WhatsApp shows in the text above buttons. */
    private static final int BODY_MAX = 1024;

    // Reply ids of rows and buttons: a prefix, then the ids it carries, separated by ':'.
    private static final String SITE = "site";
    private static final String TASK = "task";
    private static final String MORE = "more";
    private static final String ACTION = "action";
    private static final String YES = "confirm:yes";
    private static final String NO = "confirm:no";
    /** Carries the company, the Site and when it was sent (epoch seconds). */
    private static final String ANOTHER_TASK = "again";
    private static final String PHOTOS_YES = "photos:yes";
    private static final String PHOTOS_NO = "photos:no";
    private static final String PHOTOS_DONE = "photos:done";

    private final WhatsAppGateway gateway;
    private final FieldUpdateService fieldUpdates;
    private final ConversationSessions sessions;
    private final PendingPhotos photos;
    private final Clock clock;

    public FieldUpdateMenu(WhatsAppGateway gateway, FieldUpdateService fieldUpdates, ConversationSessions sessions,
            PendingPhotos photos, Clock clock) {
        this.gateway = gateway;
        this.fieldUpdates = fieldUpdates;
        this.sessions = sessions;
        this.photos = photos;
        this.clock = clock;
    }

    /** One message from a Linked number that is not BAJA / STOP. */
    public void handle(LinkedNumber sender, InboundMessage message) {
        try {
            Optional<ConversationSession> current = sessions.current(sender);
            if (current.isEmpty()) {
                begin(sender, message);
                return;
            }
            ConversationSession session = current.get();
            session.touch(clock.instant());
            if (!accept(sender, session, message)) {
                say(sender, BotText.USE_BUTTONS);
                show(sender, session);
            }
        } catch (Changed e) {
            sessions.end(sender.waId());
            say(sender, BotText.CHANGED);
        }
    }

    private boolean accept(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        return switch (session.getStep()) {
            case PICK_SITE -> acceptSite(sender, session, message);
            case PICK_TASK -> acceptTask(sender, session, message);
            case PICK_ACTION -> acceptAction(sender, session, message);
            case WRITE_NOTE -> acceptNote(sender, session, message);
            case ASK_PHOTOS -> acceptPhotoChoice(sender, session, message);
            case ADD_PHOTOS -> acceptPhoto(sender, session, message);
            case CONFIRM -> acceptConfirmation(sender, session, message);
        };
    }

    private void show(LinkedNumber sender, ConversationSession session) {
        switch (session.getStep()) {
            case PICK_SITE -> showSites(sender, session);
            case PICK_TASK -> showTasks(sender, session);
            case PICK_ACTION -> showActions(sender, session);
            case WRITE_NOTE -> askNote(sender, session);
            case ASK_PHOTOS -> askPhotos(sender, session);
            case ADD_PHOTOS -> askForPhotos(sender, session);
            case CONFIRM -> showSummary(sender, session);
        }
    }

    // --- no conversation yet: step A, or B for the Site of "Another task" -------------------------------------------

    /**
     * Any message without a conversation (none yet, or it went idle) starts at step A. A tapped button or row
     * belongs to a conversation that is gone, so {@code EXPIRED} comes first and the tap is not taken as a choice;
     * only "Another task", which carries when it was sent, goes straight to B while it is younger than
     * {@link ConversationSession#IDLE_LIMIT}.
     */
    private void begin(LinkedNumber sender, InboundMessage message) {
        Instant now = clock.instant();
        ConversationSession session = new ConversationSession(sender.waId(), sender.userId(), now);
        Optional<String[]> another = reply(message, ANOTHER_TASK, 3)
                .filter(ids -> now.isBefore(sentAt(ids[2]).plus(ConversationSession.IDLE_LIMIT)));
        if (another.isPresent()) {
            enterSite(sender, session, siteOf(sender, another.get()));
            return;
        }
        if (message.kind() == InboundMessage.Kind.REPLY) {
            say(sender, BotText.EXPIRED);
        }
        List<SiteChoice> sites = fieldUpdates.sites(sender.userId());
        if (sites.isEmpty()) {
            say(sender, BotText.NO_SITES);
        } else if (sites.size() == 1) {
            enterSite(sender, session, sites.getFirst());
        } else {
            showSites(sender, session, sites);
        }
    }

    // --- A. PICK_SITE ------------------------------------------------------------------------------------------------

    private boolean acceptSite(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (isReply(message, MORE)) {
            session.showPage(session.getPage() + 1);
            showSites(sender, session);
            return true;
        }
        Optional<String[]> site = reply(message, SITE, 2);
        site.ifPresent(ids -> enterSite(sender, session, siteOf(sender, ids)));
        return site.isPresent();
    }

    private void showSites(LinkedNumber sender, ConversationSession session) {
        showSites(sender, session, fieldUpdates.sites(sender.userId()));
    }

    /** With more than one company, each row says which company the Site belongs to. */
    private void showSites(LinkedNumber sender, ConversationSession session, List<SiteChoice> sites) {
        if (sites.isEmpty()) {
            sessions.end(sender.waId());
            say(sender, BotText.NO_SITES);
            return;
        }
        boolean manyCompanies = sites.stream().map(SiteChoice::organizationId).distinct().count() > 1;
        List<ListRow> rows = page(session, sites, site -> new ListRow(id(SITE, site.organizationId(), site.siteId()),
                site.siteName(), manyCompanies ? site.organizationName() : null), sender.language());
        sessions.save(session);
        gateway.sendList(sender.waId(), BotText.PICK_SITE.in(sender.language()), BotText.CHOOSE.in(sender.language()),
                rows);
    }

    /** A Site the user can still see, from the ids a row or button carried; {@link Changed} otherwise. */
    private SiteChoice siteOf(LinkedNumber sender, String[] ids) {
        UUID organizationId = uuid(ids[0]);
        UUID siteId = uuid(ids[1]);
        return fieldUpdates.sites(sender.userId()).stream()
                .filter(site -> site.organizationId().equals(organizationId) && site.siteId().equals(siteId))
                .findFirst().orElseThrow(Changed::new);
    }

    private void enterSite(LinkedNumber sender, ConversationSession session, SiteChoice site) {
        session.chooseSite(site.organizationId(), site.siteId());
        showTasks(sender, session);
    }

    // --- B. PICK_TASK ------------------------------------------------------------------------------------------------

    private boolean acceptTask(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (isReply(message, MORE)) {
            session.showPage(session.getPage() + 1);
            showTasks(sender, session);
            return true;
        }
        Optional<String[]> ids = reply(message, TASK, 2);
        if (ids.isEmpty()) {
            return false;
        }
        TaskChoice task = taskOf(sender, session, uuid(ids.get()[0]), uuid(ids.get()[1]));
        session.chooseTask(task.phaseId(), task.taskId());
        showActions(sender, session);
        return true;
    }

    /** No open Task left to change ends the conversation with {@code NO_TASKS}. */
    private void showTasks(LinkedNumber sender, ConversationSession session) {
        SiteTasks site = fieldUpdates.tasks(sender.userId(), session.getOrganizationId(), session.getSiteId());
        Language language = sender.language();
        if (site.tasks().isEmpty()) {
            sessions.end(sender.waId());
            gateway.sendText(sender.waId(), BotText.NO_TASKS.in(language, Map.of("site", site.siteName())));
            return;
        }
        List<ListRow> rows = page(session, site.tasks(), task -> new ListRow(id(TASK, task.phaseId(), task.taskId()),
                task.taskName(), task.phaseName() + " · " + status(task.status()).in(language)), language);
        sessions.save(session);
        gateway.sendList(sender.waId(), BotText.PICK_TASK.in(language, Map.of("site", site.siteName())),
                BotText.CHOOSE.in(language), rows);
    }

    /** An open Task of the chosen Site the user may still change; {@link Changed} otherwise. */
    private TaskChoice taskOf(LinkedNumber sender, ConversationSession session, UUID phaseId, UUID taskId) {
        return fieldUpdates.tasks(sender.userId(), session.getOrganizationId(), session.getSiteId()).tasks().stream()
                .filter(task -> task.phaseId().equals(phaseId) && task.taskId().equals(taskId))
                .findFirst().orElseThrow(Changed::new);
    }

    // --- C. PICK_ACTION ----------------------------------------------------------------------------------------------

    private boolean acceptAction(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        Optional<FieldAction> action = reply(message, ACTION, 1).flatMap(ids -> Arrays.stream(FieldAction.values())
                .filter(candidate -> candidate.name().equals(ids[0])).findFirst());
        if (action.isEmpty()) {
            return false;
        }
        TaskChoice task = taskOf(sender, session, session.getPhaseId(), session.getTaskId());
        if (!action.get().appliesTo(task.status())) {
            throw new Changed();
        }
        session.chooseAction(action.get());
        show(sender, session);
        return true;
    }

    /** The actions the Task's status allows: {@code TODO} → Start / Finish / Note, {@code IN_PROGRESS} → Finish / Note. */
    private void showActions(LinkedNumber sender, ConversationSession session) {
        TaskChoice task = taskOf(sender, session, session.getPhaseId(), session.getTaskId());
        Language language = sender.language();
        List<Button> buttons = Arrays.stream(FieldAction.values())
                .filter(action -> action.appliesTo(task.status()))
                .map(action -> new Button(id(ACTION, action.name()),
                        ActionTexts.of(action).button().in(language)))
                .toList();
        sessions.save(session);
        gateway.sendButtons(sender.waId(), BotText.PICK_ACTION.in(language, Map.of("task", task.taskName())),
                buttons);
    }

    // --- D. WRITE_NOTE -----------------------------------------------------------------------------------------------

    /** Typed text of 1–1,000 characters (spaces around do not count); longer is {@code NOTE_TOO_LONG}, ask again. */
    private boolean acceptNote(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (message.kind() != InboundMessage.Kind.TEXT || message.text() == null || message.text().isBlank()) {
            return false;
        }
        String text = message.text().strip();
        if (text.length() > ProgressNote.MAX_TEXT) {
            sessions.save(session);
            say(sender, BotText.NOTE_TOO_LONG);
            return true;
        }
        session.writeNote(text);
        show(sender, session);
        return true;
    }

    private void askNote(LinkedNumber sender, ConversationSession session) {
        sessions.save(session);
        say(sender, BotText.WRITE_NOTE);
    }

    // --- E. ASK_PHOTOS -----------------------------------------------------------------------------------------------

    private boolean acceptPhotoChoice(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (isReply(message, PHOTOS_YES)) {
            session.addPhotos();
        } else if (isReply(message, PHOTOS_NO)) {
            session.review();
        } else {
            return false;
        }
        show(sender, session);
        return true;
    }

    private void askPhotos(LinkedNumber sender, ConversationSession session) {
        Language language = sender.language();
        sessions.save(session);
        gateway.sendButtons(sender.waId(), BotText.ASK_PHOTOS.in(language), List.of(
                new Button(PHOTOS_YES, BotText.YES.in(language)), new Button(PHOTOS_NO, BotText.NO.in(language))));
    }

    // --- F. ADD_PHOTOS -----------------------------------------------------------------------------------------------

    /**
     * A JPEG or PNG is stored at once and needs no answer; past five it is {@code PHOTO_LIMIT} and not kept. Done
     * goes to the summary, with or without photos.
     */
    private boolean acceptPhoto(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (isReply(message, PHOTOS_DONE)) {
            session.review();
            show(sender, session);
            return true;
        }
        if (message.kind() != InboundMessage.Kind.IMAGE || message.mediaId() == null) {
            return false;
        }
        PendingPhotos.Added added = photos.add(sender.waId(), message.mediaId(), message.mimeType());
        if (added == PendingPhotos.Added.REFUSED) {
            return false;
        }
        sessions.save(session);
        if (added == PendingPhotos.Added.LIMIT) {
            gateway.sendButtons(sender.waId(), BotText.PHOTO_LIMIT.in(sender.language()), List.of(done(sender)));
        }
        return true;
    }

    private void askForPhotos(LinkedNumber sender, ConversationSession session) {
        sessions.save(session);
        gateway.sendButtons(sender.waId(), BotText.SEND_PHOTOS.in(sender.language()), List.of(done(sender)));
    }

    private static Button done(LinkedNumber sender) {
        return new Button(PHOTOS_DONE, BotText.DONE.in(sender.language()));
    }

    // --- G. CONFIRM --------------------------------------------------------------------------------------------------

    private boolean acceptConfirmation(LinkedNumber sender, ConversationSession session, InboundMessage message) {
        if (isReply(message, YES)) {
            fieldUpdates.apply(session);
            gateway.sendButtons(sender.waId(), BotText.SAVED.in(sender.language()), List.of(new Button(
                    id(ANOTHER_TASK, session.getOrganizationId(), session.getSiteId(),
                            clock.instant().getEpochSecond()),
                    BotText.ANOTHER_TASK.in(sender.language()))));
            return true;
        }
        if (isReply(message, NO)) {
            sessions.end(sender.waId());
            say(sender, BotText.CANCELLED);
            return true;
        }
        return false;
    }

    /**
     * The summary names the Site and the Task as stored now, never as the person typed or saw them, and counts the
     * pending photos. A note too long for the 1,024 characters WhatsApp shows above buttons is shortened here only;
     * YES saves all of it.
     */
    private void showSummary(LinkedNumber sender, ConversationSession session) {
        SiteTasks site = fieldUpdates.tasks(sender.userId(), session.getOrganizationId(), session.getSiteId());
        TaskChoice task = site.tasks().stream().filter(t -> t.taskId().equals(session.getTaskId())).findFirst()
                .orElseThrow(Changed::new);
        Language language = sender.language();
        String head = BotText.SUMMARY.in(language, Map.of("site", site.siteName(), "task", task.taskName(),
                "action", ActionTexts.of(session.getAction()).summary().in(language)));
        int photoCount = photos.count(sender.waId());
        String photoLine = photoCount == 0 ? "" : "\n" + (photoCount == 1 ? BotText.ONE_PHOTO_LINE.in(language)
                : BotText.PHOTOS_LINE.in(language, Map.of("n", String.valueOf(photoCount))));
        String tail = photoLine + "\n\n" + BotText.SAVE_IT.in(language);
        StringBuilder body = new StringBuilder(head);
        if (session.getNoteText() != null) {
            int room = BODY_MAX - head.length() - tail.length() - 1
                    - BotText.NOTE_LINE.in(language, Map.of("note", "")).length();
            body.append('\n').append(BotText.NOTE_LINE.in(language,
                    Map.of("note", cut(session.getNoteText(), Math.max(room, 1)))));
        }
        body.append(tail);
        sessions.save(session);
        gateway.sendButtons(sender.waId(), body.toString(), List.of(
                new Button(YES, BotText.YES.in(language)), new Button(NO, BotText.NO.in(language))));
    }

    // --- labels ------------------------------------------------------------------------------------------------------

    /**
     * What an action's button says, and how the summary names it. The texts stay here rather than on
     * {@link FieldAction}, so the model does not depend on the conversation.
     */
    private record ActionTexts(BotText button, BotText summary) {
        static ActionTexts of(FieldAction action) {
            return switch (action) {
                case START -> new ActionTexts(BotText.START, BotText.STARTED);
                case FINISH -> new ActionTexts(BotText.FINISH, BotText.FINISHED);
                case NOTE -> new ActionTexts(BotText.NOTE, BotText.NOTE);
            };
        }
    }

    private static BotText status(ActivityStatus status) {
        return switch (status) {
            case TODO -> BotText.STATUS_TODO;
            case IN_PROGRESS -> BotText.STATUS_IN_PROGRESS;
            case DONE -> throw new IllegalArgumentException("A finished Task is never offered");
        };
    }

    // --- lists, replies ----------------------------------------------------------------------------------------------

    /**
     * The rows of the session's page: everything when it fits in one list, otherwise 9 rows and "See more". A page
     * past the end (the list got shorter) goes back to the first.
     */
    private static <T> List<ListRow> page(ConversationSession session, List<T> items, Function<T, ListRow> row,
            Language language) {
        int from = session.getPage() * PAGE_SIZE;
        if (from >= items.size()) {
            session.showPage(0);
            from = 0;
        }
        int remaining = items.size() - from;
        int shown = remaining <= MAX_ROWS ? remaining : PAGE_SIZE;
        List<ListRow> rows = new ArrayList<>(items.subList(from, from + shown).stream().map(row)
                .map(FieldUpdateMenu::fit).toList());
        if (shown < remaining) {
            rows.add(new ListRow(MORE, BotText.SEE_MORE.in(language), null));
        }
        return rows;
    }

    /** WhatsApp takes at most 24 characters in a row title and 72 in its description. */
    private static ListRow fit(ListRow row) {
        return new ListRow(row.id(), cut(row.title(), ROW_TITLE_MAX), cut(row.description(), ROW_DESCRIPTION_MAX));
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void say(LinkedNumber sender, BotText text) {
        gateway.sendText(sender.waId(), text.in(sender.language()));
    }

    private static String id(String prefix, Object... parts) {
        StringBuilder id = new StringBuilder(prefix);
        for (Object part : parts) {
            id.append(':').append(part);
        }
        return id.toString();
    }

    private static boolean isReply(InboundMessage message, String id) {
        return message.kind() == InboundMessage.Kind.REPLY && id.equals(message.replyId());
    }

    /** The ids a tapped row or button carries after {@code prefix}, when it has that prefix and that many ids. */
    private static Optional<String[]> reply(InboundMessage message, String prefix, int parts) {
        if (message.kind() != InboundMessage.Kind.REPLY || message.replyId() == null) {
            return Optional.empty();
        }
        String[] split = message.replyId().split(":", -1);
        if (split.length != parts + 1 || !split[0].equals(prefix)) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOfRange(split, 1, split.length));
    }

    /** When a button was sent, from the epoch seconds its id carries; a malformed one counts as long ago. */
    private static Instant sentAt(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (NumberFormatException e) {
            return Instant.EPOCH;
        }
    }

    /** A malformed id in a reply is a choice that does not exist. */
    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new Changed();
        }
    }
}

package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.ProgressNote;
import org.andre.construx.modules.project.model.UpdateSource;
import org.andre.construx.modules.project.repository.ProgressNoteRepository;
import org.andre.construx.shared.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Progress notes of a Task. Reading follows the Task's {@code VIEW_ACTIVITY}; writing follows {@code MANAGE_ACTIVITY},
 * where OWN scope (Worker) only reaches Tasks whose Team the caller is in, exactly like start and complete. The
 * dashboard and WhatsApp both write through {@link #add}, so a note never reaches further than the app. There is no
 * edit or delete.
 */
@Service
public class ProgressNoteService {
    public static final String AUDIT_ENTITY = "PROGRESS_NOTE";

    private final ActivityService activities;
    private final ProgressNoteRepository notes;
    private final PersonDirectory directory;
    private final AuditService audit;

    public ProgressNoteService(ActivityService activities, ProgressNoteRepository notes, PersonDirectory directory,
                               AuditService audit) {
        this.activities = activities;
        this.notes = notes;
        this.directory = directory;
        this.audit = audit;
    }

    /** {@code name} is null when the author has no profile name. */
    public record AuthorData(UUID membershipId, String name) {}

    public record ProgressNoteData(UUID id, String text, UpdateSource source, AuthorData author,
                                   Instant createdAt) {}

    /** The audit after-state of a new note. */
    record AuditState(UUID activityId, String text, UpdateSource source) {}

    /** Newest first. 404 outside the scope's Site and phase, 403 without {@code VIEW_ACTIVITY} for this Task. */
    @Transactional(readOnly = true)
    public List<ProgressNoteData> list(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = activities.load(site, goalId, activityId);
        site.require(PermissionAction.VIEW_ACTIVITY, teamMember(activity, site.membership()));
        return toData(notes.findByActivityNewestFirst(activityId));
    }

    /**
     * Writes a note by the scope's member. 404 outside the Site and phase, 403 without {@code MANAGE_ACTIVITY} for this
     * Task, 400 {@code PROGRESS_NOTE_EMPTY} for blank text. Records {@code PROGRESS_NOTE CREATE} with the source.
     */
    @Transactional
    public ProgressNoteData add(SiteScope site, UUID goalId, UUID activityId, String text, UpdateSource source) {
        Activity activity = activities.load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY, teamMember(activity, site.membership()));
        ProgressNote note = notes.save(new ProgressNote(activity, site.membership(), text, source, Instant.now()));
        audit.record(AUDIT_ENTITY, note.getId(), "CREATE", site.membershipId(), null,
                new AuditState(activityId, note.getText(), note.getSource()));
        return toData(List.of(note)).get(0);
    }

    /** The caller's membership when they are in the Task's Team, which is what OWN scope matches; null otherwise. */
    private static UUID teamMember(Activity activity, OrganizationMembership member) {
        return activity.isOnTeam(member.getId()) ? member.getId() : null;
    }

    private List<ProgressNoteData> toData(List<ProgressNote> list) {
        Map<UUID, Person> people = directory.byUserId(list.stream()
                .map(n -> n.getAuthor().getUserAccount().getId()).toList());
        return list.stream().map(n -> new ProgressNoteData(n.getId(), n.getText(), n.getSource(),
                new AuthorData(n.getAuthor().getId(),
                        PersonDirectory.of(people, n.getAuthor().getUserAccount().getId()).fullName()),
                n.getCreatedAt())).toList();
    }
}

package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.ProgressNote;
import org.andre.construx.modules.project.model.ProgressNotePhoto;
import org.andre.construx.modules.project.model.ProgressNotePhoto.StoredPhoto;
import org.andre.construx.modules.project.model.UpdateSource;
import org.andre.construx.modules.project.repository.ProgressNotePhotoRepository;
import org.andre.construx.modules.project.repository.ProgressNoteRepository;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Progress notes of a Task. Reading follows the Task's {@code VIEW_ACTIVITY}; writing follows {@code MANAGE_ACTIVITY},
 * where OWN scope (Worker) only reaches Tasks whose Team the caller is in, exactly like start and complete. The
 * dashboard and WhatsApp both write through {@link #add}, so a note never reaches further than the app. There is no
 * edit or delete.
 *
 * <p>Photos follow the Pin photo pattern: the bytes go straight to storage through a presigned upload, and a note
 * then names the keys. Lists hand out presigned download links that expire after {@link #VIEW_TTL}.
 */
@Service
public class ProgressNoteService {
    public static final String AUDIT_ENTITY = "PROGRESS_NOTE";
    /** Where the WhatsApp bot keeps the photos it downloaded before the person confirms. */
    public static final String WHATSAPP_PENDING_PREFIX = "whatsapp/pending/";
    public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
    public static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofMinutes(10);

    private final ActivityService activities;
    private final ProgressNoteRepository notes;
    private final ProgressNotePhotoRepository photos;
    private final PersonDirectory directory;
    private final ObjectStorage storage;
    private final AuditService audit;

    public ProgressNoteService(ActivityService activities, ProgressNoteRepository notes,
                               ProgressNotePhotoRepository photos, PersonDirectory directory, ObjectStorage storage,
                               AuditService audit) {
        this.activities = activities;
        this.notes = notes;
        this.photos = photos;
        this.directory = directory;
        this.storage = storage;
        this.audit = audit;
    }

    /** {@code name} is null when the author has no profile name. */
    public record AuthorData(UUID membershipId, String name) {}

    /** {@code url} is a presigned download that expires after ten minutes. */
    public record PhotoData(UUID id, URI url) {}

    /** {@code text} is null for a note of photos only; {@code photos} are in the order they were sent. */
    public record ProgressNoteData(UUID id, String text, UpdateSource source, AuthorData author,
                                   Instant createdAt, List<PhotoData> photos) {}

    /** {@code key} is sent back as a photo key once the bytes are uploaded. */
    public record PhotoUploadData(String key, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    /** The audit after-state of a new note. */
    record AuditState(UUID activityId, String text, int photoCount, UpdateSource source) {}

    /** Newest first. 404 outside the scope's Site and phase, 403 without {@code VIEW_ACTIVITY} for this Task. */
    @Transactional(readOnly = true)
    public List<ProgressNoteData> list(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = activities.load(site, goalId, activityId);
        site.require(PermissionAction.VIEW_ACTIVITY, teamMember(activity, site.membership()));
        return toData(notes.findByActivityNewestFirst(activityId));
    }

    /**
     * Presigns an upload for a photo of a note on this Task; it only counts once a note names its key. Same right as
     * writing a note. 400 {@code PROGRESS_NOTE_PHOTO_INVALID} unless JPEG or PNG of at most 5 MB.
     */
    @Transactional(readOnly = true)
    public PhotoUploadData requestPhotoUpload(SiteScope site, UUID goalId, UUID activityId, String contentType,
                                              long sizeBytes) {
        Activity activity = activities.load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY, teamMember(activity, site.membership()));
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!PHOTO_TYPES.contains(type) || sizeBytes <= 0 || sizeBytes > MAX_PHOTO_BYTES) {
            throw invalidPhoto("Progress note photos must be JPEG or PNG up to 5 MB");
        }
        String key = uploadPrefix(site, activityId) + UUID.randomUUID();
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, sizeBytes, UPLOAD_TTL);
        return new PhotoUploadData(key, upload.url(), upload.headers(), upload.expiresAt());
    }

    /**
     * Writes a note by the scope's member, with text, photos or both. 404 outside the Site and phase, 403 without
     * {@code MANAGE_ACTIVITY} for this Task, 400 {@code PROGRESS_NOTE_EMPTY} with neither, 400
     * {@code PROGRESS_NOTE_TOO_MANY_PHOTOS} past five. Records {@code PROGRESS_NOTE CREATE} with the source.
     *
     * <p>{@code photoKeys} name objects already in storage, attached in the order given (repeats count once). Where a
     * key may come from depends on the source: an {@code APP} key must have been issued by
     * {@link #requestPhotoUpload} for this Task; a {@code WHATSAPP} key must sit under
     * {@link #WHATSAPP_PENDING_PREFIX}, where the bot stored it. Each must hold a JPEG or PNG of at most 5 MB that
     * no other note uses, else 400 {@code PROGRESS_NOTE_PHOTO_INVALID}. The note keeps the key as it is: the caller
     * must not delete the object afterwards.
     */
    @Transactional
    public ProgressNoteData add(SiteScope site, UUID goalId, UUID activityId, String text, List<String> photoKeys,
                                UpdateSource source) {
        Activity activity = activities.load(site, goalId, activityId);
        site.require(PermissionAction.MANAGE_ACTIVITY, teamMember(activity, site.membership()));
        List<String> keys = photoKeys == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(photoKeys));
        if (keys.size() > ProgressNote.MAX_PHOTOS) {
            throw new RuleViolationException("PROGRESS_NOTE_TOO_MANY_PHOTOS",
                    "A progress note holds at most " + ProgressNote.MAX_PHOTOS + " photos");
        }
        String prefix = source == UpdateSource.WHATSAPP ? WHATSAPP_PENDING_PREFIX : uploadPrefix(site, activityId);
        List<StoredPhoto> stored = keys.stream().map(key -> stored(key, prefix)).toList();
        ProgressNote note = notes.save(new ProgressNote(activity, site.membership(), text, stored, source,
                Instant.now()));
        audit.record(AUDIT_ENTITY, note.getId(), "CREATE", site.membershipId(), null,
                new AuditState(activityId, note.getText(), note.getPhotos().size(), note.getSource()));
        return toData(List.of(note)).get(0);
    }

    /** Text-only shorthand of {@link #add(SiteScope, UUID, UUID, String, List, UpdateSource)}. */
    @Transactional
    public ProgressNoteData add(SiteScope site, UUID goalId, UUID activityId, String text, UpdateSource source) {
        return add(site, goalId, activityId, text, List.of(), source);
    }

    private static String uploadPrefix(SiteScope site, UUID activityId) {
        return "orgs/%s/projects/%s/activities/%s/progress-notes/"
                .formatted(site.organizationId(), site.siteId(), activityId);
    }

    /** What storage holds under {@code key}, once the key is shown to be one this note may take. */
    private StoredPhoto stored(String key, String prefix) {
        if (key == null || !key.startsWith(prefix) || key.contains("..") || photos.existsByStorageKey(key)) {
            throw invalidPhoto("The photo does not belong to this task");
        }
        ObjectStorage.StoredObject object = storage.head(key)
                .orElseThrow(() -> invalidPhoto("The photo was not uploaded"));
        String type = object.contentType() == null ? "" : object.contentType().toLowerCase(Locale.ROOT);
        if (object.sizeBytes() > MAX_PHOTO_BYTES || !PHOTO_TYPES.contains(type)) {
            throw invalidPhoto("Progress note photos must be JPEG or PNG up to 5 MB");
        }
        return new StoredPhoto(key, type, object.sizeBytes());
    }

    private static RuleViolationException invalidPhoto(String message) {
        return new RuleViolationException("PROGRESS_NOTE_PHOTO_INVALID", message);
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
                n.getCreatedAt(), photoData(n.getPhotos()))).toList();
    }

    private List<PhotoData> photoData(List<ProgressNotePhoto> list) {
        return list.stream().map(p -> new PhotoData(p.getId(), storage.presignDownload(p.getStorageKey(), VIEW_TTL)))
                .toList();
    }
}

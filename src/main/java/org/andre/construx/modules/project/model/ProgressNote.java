package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.ProgressNotePhoto.StoredPhoto;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.validation.DomainValidation;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A dated entry on one Task about how the work is going. Immutable once written: there is no setter, and nothing
 * edits or deletes it except deleting its Task.
 */
@Entity
@Table(name = "progress_note")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProgressNote {
    public static final int MAX_TEXT = 1000;
    public static final int MAX_PHOTOS = 5;

    @Id
    private UUID id;

    /** Deleting the Task deletes its notes in the database (the migration's foreign key cascades too). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Activity activity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_membership_id", nullable = false, updatable = false)
    private OrganizationMembership author;

    /** Trimmed; null when the note has no text. */
    @Column(length = MAX_TEXT, updatable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private UpdateSource source;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** In the order they were sent; saved with the note and never changed after. */
    @OneToMany(mappedBy = "note", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("position asc")
    private List<ProgressNotePhoto> photos = new ArrayList<>();

    /**
     * A note needs text, photos or both: neither is {@code PROGRESS_NOTE_EMPTY}, more than {@value #MAX_PHOTOS}
     * photos is {@code PROGRESS_NOTE_TOO_MANY_PHOTOS}. Photos take positions 0 to 4 in the order given.
     */
    public ProgressNote(Activity activity, OrganizationMembership author, String text, List<StoredPhoto> photos,
                        UpdateSource source, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.activity = DomainValidation.required(activity, "Task is required");
        this.author = DomainValidation.required(author, "Author is required");
        this.source = DomainValidation.required(source, "Source is required");
        this.createdAt = DomainValidation.required(createdAt, "Creation time is required");
        this.text = text == null || text.isBlank() ? null : text.strip();
        List<StoredPhoto> sent = photos == null ? List.of() : photos;
        if (this.text == null && sent.isEmpty()) {
            throw new RuleViolationException("PROGRESS_NOTE_EMPTY", "A progress note needs text or photos");
        }
        if (this.text != null && this.text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("A progress note is at most " + MAX_TEXT + " characters");
        }
        if (sent.size() > MAX_PHOTOS) {
            throw new RuleViolationException("PROGRESS_NOTE_TOO_MANY_PHOTOS",
                    "A progress note holds at most " + MAX_PHOTOS + " photos");
        }
        for (StoredPhoto photo : sent) {
            this.photos.add(new ProgressNotePhoto(this, photo, this.photos.size()));
        }
    }

    public List<ProgressNotePhoto> getPhotos() {
        return List.copyOf(photos);
    }
}

package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/** One photo of a Progress note, at its place (0 to 4) in the order it was sent. Immutable like its note. */
@Entity
@Table(name = "progress_note_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProgressNotePhoto {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "progress_note_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProgressNote note;

    @Column(nullable = false, unique = true, length = 300, updatable = false)
    private String storageKey;

    @Column(nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private int position;

    ProgressNotePhoto(ProgressNote note, StoredPhoto photo, int position) {
        this.id = UUID.randomUUID();
        this.note = note;
        this.storageKey = photo.storageKey();
        this.contentType = photo.contentType();
        this.sizeBytes = photo.sizeBytes();
        this.position = position;
    }

    /** A photo already in storage, checked by the caller, about to be attached to a note. */
    public record StoredPhoto(String storageKey, String contentType, long sizeBytes) {}
}

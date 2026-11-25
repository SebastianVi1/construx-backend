package org.andre.construx.modules.messaging.service;

import org.andre.construx.modules.messaging.gateway.WhatsAppGateway;
import org.andre.construx.modules.messaging.gateway.WhatsAppGateway.Media;
import org.andre.construx.modules.messaging.model.PendingPhoto;
import org.andre.construx.modules.messaging.repository.PendingPhotoRepository;
import org.andre.construx.modules.project.model.ProgressNote;
import org.andre.construx.modules.project.model.ProgressNotePhoto;
import org.andre.construx.shared.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The photos a Linked number sends during a Field update, before YES (contract §2 {@code whatsapp_pending_photo}, §5
 * step F). Each is downloaded from Meta as it arrives and stored privately under
 * {@code whatsapp/pending/{waId}/}. YES {@link #claim claims} them for a Progress note, moving each object to a key
 * of the note; every other end of the conversation {@link #discard discards} them, rows and objects.
 */
@Component
public class PendingPhotos {
    private static final Logger log = LoggerFactory.getLogger(PendingPhotos.class);
    /** Where the photos of each number wait for YES. */
    static final String PREFIX = "whatsapp/pending/";

    /** What became of one image the number sent. */
    public enum Added {
        /** Stored as pending. */
        KEPT,
        /** The number already has {@link ProgressNote#MAX_PHOTOS}; not downloaded. */
        LIMIT,
        /** Not a JPEG or PNG of at most 5 MB; not stored. */
        REFUSED
    }

    private final PendingPhotoRepository photos;
    private final WhatsAppGateway gateway;
    private final ObjectStorage storage;

    public PendingPhotos(PendingPhotoRepository photos, WhatsAppGateway gateway, ObjectStorage storage) {
        this.photos = photos;
        this.gateway = gateway;
        this.storage = storage;
    }

    /**
     * Downloads the image Meta holds under {@code mediaId} and stores it as the number's next pending photo. The
     * number's conversation must exist. Images arrive one at a time per number, so the count does not race.
     */
    public Added add(String waId, String mediaId, String declaredType) {
        long count = photos.countByWaId(waId);
        if (count >= ProgressNote.MAX_PHOTOS) {
            return Added.LIMIT;
        }
        if (!ProgressNotePhoto.acceptsType(ProgressNotePhoto.mediaType(declaredType))) {
            return Added.REFUSED;
        }
        Media media = gateway.downloadMedia(mediaId);
        String type = ProgressNotePhoto.mediaType(media.contentType());
        long size = media.bytes().length;
        if (!ProgressNotePhoto.accepts(type, size)) {
            return Added.REFUSED;
        }
        String key = PREFIX + waId + "/" + UUID.randomUUID();
        PendingPhoto photo = photos.save(new PendingPhoto(waId, key, type, size, (int) count));
        try {
            storage.put(key, media.bytes(), type);
        } catch (RuntimeException e) {
            photos.delete(photo);
            throw e;
        }
        return Added.KEPT;
    }

    public int count(String waId) {
        return (int) photos.countByWaId(waId);
    }

    /**
     * YES: moves the number's pending photos, in the order they arrived, to keys of the Progress note that takes them
     * ({@code noteKey} hands out a new one per photo) and returns those keys. The rows go at once. The pending objects
     * are deleted once that is committed; on a rollback the copies are deleted instead, and the rows come back.
     */
    @Transactional
    public List<String> claim(String waId, Supplier<String> noteKey) {
        List<PendingPhoto> pending = photos.findByWaIdOrderByPosition(waId);
        photos.deleteAll(pending);
        List<String> from = pending.stream().map(PendingPhoto::getStorageKey).toList();
        List<String> to = new ArrayList<>();
        afterCompletion(committed -> deleteObjects(committed ? from : to));
        for (String key : from) {
            String target = noteKey.get();
            storage.copy(key, target);
            to.add(target);
        }
        return List.copyOf(to);
    }

    /**
     * Deletes the number's pending photos: the rows, and the stored objects once that is committed. Nothing happens
     * when there are none. Photos already {@link #claim claimed} by a note are not touched.
     */
    @Transactional
    public void discard(String waId) {
        List<PendingPhoto> pending = photos.findByWaIdOrderByPosition(waId);
        if (pending.isEmpty()) {
            return;
        }
        photos.deleteAll(pending);
        List<String> keys = pending.stream().map(PendingPhoto::getStorageKey).toList();
        afterCompletion(committed -> {
            if (committed) {
                deleteObjects(keys);
            }
        });
    }

    /** Runs once the surrounding transaction ends, told whether it committed; at once (as committed) without one. */
    private static void afterCompletion(Consumer<Boolean> action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.accept(true);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status == STATUS_COMMITTED);
            }
        });
    }

    private void deleteObjects(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete a WhatsApp photo from storage", e);
            }
        }
    }
}

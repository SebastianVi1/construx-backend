package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProgressNotePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Photos are written with their note (cascade); this only answers whether a key is already taken. */
public interface ProgressNotePhotoRepository extends JpaRepository<ProgressNotePhoto, UUID> {
    boolean existsByStorageKey(String storageKey);
}

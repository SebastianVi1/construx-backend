package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProgressNotePhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Photos are written with their note (cascade); this answers whether a key is taken and which keys a Task holds. */
public interface ProgressNotePhotoRepository extends JpaRepository<ProgressNotePhoto, UUID> {
    boolean existsByStorageKey(String storageKey);

    @Query("select p.storageKey from ProgressNotePhoto p where p.note.activity.id = :activityId")
    List<String> findStorageKeysByActivityId(@Param("activityId") UUID activityId);
}

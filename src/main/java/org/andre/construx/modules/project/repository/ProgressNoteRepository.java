package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.ProgressNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** Read and insert only: Progress notes are never edited or deleted. */
public interface ProgressNoteRepository extends JpaRepository<ProgressNote, UUID> {
    /** Newest first, with the author's membership and account for display. */
    @Query("select n from ProgressNote n join fetch n.author m join fetch m.userAccount "
            + "where n.activity.id = :activityId order by n.createdAt desc, n.id desc")
    List<ProgressNote> findByActivityNewestFirst(UUID activityId);
}

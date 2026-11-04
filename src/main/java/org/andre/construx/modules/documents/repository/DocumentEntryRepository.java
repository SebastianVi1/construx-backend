package org.andre.construx.modules.documents.repository;

import org.andre.construx.modules.documents.model.DocumentEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentEntryRepository extends JpaRepository<DocumentEntry, UUID> {
    List<DocumentEntry> findByFolder_IdOrderByCreatedAtDesc(UUID folderId);
    long countByFolder_Id(UUID folderId);
    Optional<DocumentEntry> findByIdAndFolder_Id(UUID id, UUID folderId);

    @Query("SELECT e FROM DocumentEntry e WHERE e.projectId = :projectId AND LOWER(e.fileName) LIKE LOWER(CONCAT('%', :term, '%')) ORDER BY e.createdAt DESC")
    List<DocumentEntry> searchByFileName(UUID projectId, String term);
}

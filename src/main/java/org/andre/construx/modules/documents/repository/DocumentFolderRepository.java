package org.andre.construx.modules.documents.repository;

import org.andre.construx.modules.documents.model.DocumentFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentFolderRepository extends JpaRepository<DocumentFolder, UUID> {
    List<DocumentFolder> findByProjectIdOrderByPositionAscNameEnAsc(UUID projectId);
    boolean existsByProjectIdAndNameEn(UUID projectId, String nameEn);
}

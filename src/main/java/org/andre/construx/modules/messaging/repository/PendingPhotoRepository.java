package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.PendingPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PendingPhotoRepository extends JpaRepository<PendingPhoto, UUID> {
    List<PendingPhoto> findByWaIdOrderByPosition(String waId);

    long countByWaId(String waId);
}

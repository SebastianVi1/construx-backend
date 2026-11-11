package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.PlanPinPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanPinPhotoRepository extends JpaRepository<PlanPinPhoto, UUID> {

    List<PlanPinPhoto> findByPinIdOrderByCreatedAtAsc(UUID pinId);

    List<PlanPinPhoto> findByPinIdInOrderByCreatedAtAsc(Collection<UUID> pinIds);

    long countByPinId(UUID pinId);

    Optional<PlanPinPhoto> findByIdAndPinId(UUID id, UUID pinId);
}

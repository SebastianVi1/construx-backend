package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FloorPlanZoneRepository extends JpaRepository<FloorPlanZone, UUID> {

    List<FloorPlanZone> findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID floorPlanId);

    Optional<FloorPlanZone> findByIdAndFloorPlanIdAndDeletedAtIsNull(UUID id, UUID floorPlanId);
}

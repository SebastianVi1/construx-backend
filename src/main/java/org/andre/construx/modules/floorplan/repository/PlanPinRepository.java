package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.PlanPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanPinRepository extends JpaRepository<PlanPin, UUID> {

    List<PlanPin> findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID floorPlanId);

    Optional<PlanPin> findByIdAndFloorPlanIdAndDeletedAtIsNull(UUID id, UUID floorPlanId);
}

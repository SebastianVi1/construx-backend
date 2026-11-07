package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.FloorPlanCalibration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FloorPlanCalibrationRepository extends JpaRepository<FloorPlanCalibration, UUID> {
    Optional<FloorPlanCalibration> findByFloorPlanId(UUID floorPlanId);
    void deleteByFloorPlanId(UUID floorPlanId);
}

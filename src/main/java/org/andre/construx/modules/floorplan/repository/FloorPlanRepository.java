package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FloorPlanRepository extends JpaRepository<FloorPlan, UUID> {
    Optional<FloorPlan> findByIdAndProjectId(UUID id, UUID projectId);

    List<FloorPlan> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}

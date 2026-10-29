package org.andre.construx.modules.plan.repository;

import org.andre.construx.modules.plan.model.PlanStatus;
import org.andre.construx.modules.plan.model.ProjectPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPlanRepository extends JpaRepository<ProjectPlan, UUID> {
    Optional<ProjectPlan> findByIdAndProjectId(UUID id, UUID projectId);

    List<ProjectPlan> findByProjectIdAndStatusOrderByCreatedAtDesc(UUID projectId, PlanStatus status);
}

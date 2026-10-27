package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByProjectIdOrderByDueAtAsc(UUID projectId);

    Optional<Goal> findByIdAndProjectId(UUID id, UUID projectId);
}

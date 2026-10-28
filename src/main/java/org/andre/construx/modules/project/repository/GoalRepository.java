package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByProjectIdOrderByDueAtAsc(UUID projectId);

    Optional<Goal> findByIdAndProjectId(UUID id, UUID projectId);

    /** Sum of the phase budgets of one site; zero when none is set. */
    @Query("select coalesce(sum(g.allocatedBudget), 0) from Goal g where g.project.id = :projectId")
    BigDecimal sumBudgetByProject(UUID projectId);
}

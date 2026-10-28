package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    /** Rows of [projectId, activityCount, doneCount]; projects without activities are absent. */
    @Query("select g.project.id, count(a), "
            + "sum(case when a.status = org.andre.construx.modules.project.model.ActivityStatus.DONE then 1 else 0 end) "
            + "from Activity a join a.goal g where g.project.id in :projectIds group by g.project.id")
    List<Object[]> countByProject(Collection<UUID> projectIds);

    boolean existsByAssigneeAccessId(UUID accessId);

    /** Rows of [goalId, activityCount, doneCount] for one site; goals without activities are absent. */
    @Query("select a.goal.id, count(a), "
            + "sum(case when a.status = org.andre.construx.modules.project.model.ActivityStatus.DONE then 1 else 0 end) "
            + "from Activity a where a.goal.project.id = :projectId group by a.goal.id")
    List<Object[]> countByGoal(UUID projectId);

    boolean existsByGoalId(UUID goalId);

    /** Rows of [goalId, sum of task budgets] for one site; goals without task budgets are absent. */
    @Query("select a.goal.id, sum(a.allocatedBudget) from Activity a "
            + "where a.goal.project.id = :projectId and a.allocatedBudget is not null group by a.goal.id")
    List<Object[]> sumBudgetByGoal(UUID projectId);

    /** Sum of the task budgets of one phase; zero when none is set. */
    @Query("select coalesce(sum(a.allocatedBudget), 0) from Activity a where a.goal.id = :goalId")
    BigDecimal sumBudgetOfGoal(UUID goalId);

    @Query("select a from Activity a join fetch a.assigneeAccess acc join fetch acc.organizationMembership "
            + "where a.goal.id = :goalId order by a.dueAt, a.name")
    List<Activity> findByGoalWithAssignee(UUID goalId);

    Optional<Activity> findByIdAndGoalId(UUID id, UUID goalId);

    /** The membership's assigned tasks across every site of its organization. */
    @Query("select a from Activity a join fetch a.goal g join fetch g.project join fetch a.assigneeAccess acc "
            + "join fetch acc.organizationMembership m where m.id = :membershipId "
            + "and g.project.organization.id = :organizationId order by a.dueAt, a.name")
    List<Activity> findAssignedTo(UUID organizationId, UUID membershipId);
}

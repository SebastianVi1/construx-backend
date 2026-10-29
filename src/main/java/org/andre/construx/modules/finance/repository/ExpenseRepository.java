package org.andre.construx.modules.finance.repository;

import org.andre.construx.modules.finance.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {
    List<Expense> findByProjectIdOrderBySpentOnDescCreatedAtDesc(UUID projectId);

    Optional<Expense> findByIdAndProjectId(UUID id, UUID projectId);

    List<Expense> findByActivityIdOrderBySpentOnDescCreatedAtDesc(UUID activityId);

    /** Sum of the expenses charged to one task; zero when none. */
    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.activity.id = :activityId")
    BigDecimal sumOfActivity(UUID activityId);

    /** Rows of {category, sum(amount)} for the categories that have expenses. */
    @Query("select e.category, sum(e.amount) from Expense e where e.project.id = :projectId group by e.category")
    List<Object[]> sumByCategory(UUID projectId);
}

package org.andre.construx.modules.finance.repository;

import org.andre.construx.modules.finance.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** Rows of {projectId, sum(amount)} for all sites in the organization (regardless of budget). */
    @Query("select e.project.id, sum(e.amount) from Expense e where e.project.organization.id = :organizationId group by e.project.id")
    List<Object[]> sumByProjectInOrganization(@Param("organizationId") UUID organizationId);

    /**
     * Rows of {category, sum(amount)} for budgeted, non-archived sites in the organization.
     * "Budgeted" means allocatedBudget is not null; ARCHIVED sites are excluded.
     */
    @Query("select e.category, sum(e.amount) from Expense e "
            + "where e.project.organization.id = :organizationId "
            + "and e.project.allocatedBudget is not null "
            + "and e.project.status <> org.andre.construx.modules.project.model.ProjectStatus.ARCHIVED "
            + "group by e.category")
    List<Object[]> sumByCategoryForBudgetedSites(@Param("organizationId") UUID organizationId);

    /**
     * Rows of {year, month, sum(amount)} for budgeted, non-archived sites, filtered by date range.
     * Uses HQL year()/month() for H2 and PostgreSQL compatibility.
     */
    @Query("select year(e.spentOn), month(e.spentOn), sum(e.amount) from Expense e "
            + "where e.project.organization.id = :organizationId "
            + "and e.project.allocatedBudget is not null "
            + "and e.project.status <> org.andre.construx.modules.project.model.ProjectStatus.ARCHIVED "
            + "and e.spentOn between :from and :to "
            + "group by year(e.spentOn), month(e.spentOn) "
            + "order by year(e.spentOn), month(e.spentOn)")
    List<Object[]> sumByMonthForBudgetedSites(@Param("organizationId") UUID organizationId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);
}

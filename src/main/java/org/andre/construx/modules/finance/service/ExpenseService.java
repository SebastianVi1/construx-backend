package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.BudgetAllocation;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Site expenses and the budget summary. Callers arrive with a {@link SiteScope} (membership
 * and tenant-filtered site already 404-checked), then VIEW_BUDGET to read or MANAGE_BUDGET to write (403), then the expense inside that site (404).
 * One exception (ADR 0005): the current Lead of a task reads that task's expenses and budget, records expenses on
 * it, and edits or deletes the ones they recorded, without the budget permissions.
 */
@Service
public class ExpenseService {
    private final ExpenseRepository expenses;
    private final ProjectService projects;
    private final ActivityService activities;

    public ExpenseService(ExpenseRepository expenses, ProjectService projects, ActivityService activities) {
        this.expenses = expenses;
        this.projects = projects;
        this.activities = activities;
    }

    /** {@code activityId} and {@code createdByMembershipId} are null when the expense has no task or no recorder. */
    public record ExpenseData(UUID id, UUID projectId, UUID activityId, UUID createdByMembershipId, BigDecimal amount,
                              LocalDate spentOn, String description, ExpenseCategory category) {}

    /** The task's own budget against what was charged to it; {@code remaining} is null without a budget. */
    public record TaskBudget(BigDecimal allocatedBudget, BigDecimal spent, BigDecimal remaining) {}

    public record CategoryTotal(ExpenseCategory category, BigDecimal amount) {}

    /**
     * {@code remaining} is null without a budget and negative on an overrun. {@code allocatedToPhases} is the sum
     * of the phase budgets and {@code unallocated} what the site has left to give phases, null without a budget.
     */
    public record BudgetSummary(BigDecimal allocatedBudget, BigDecimal spent, BigDecimal remaining,
                                BigDecimal allocatedToPhases, BigDecimal unallocated, List<CategoryTotal> byCategory) {}

    @Transactional(readOnly = true)
    public List<ExpenseData> list(SiteScope site) {
        site.require(PermissionAction.VIEW_BUDGET);
        return expenses.findByProjectIdOrderBySpentOnDescCreatedAtDesc(site.siteId()).stream()
                .map(ExpenseService::data).toList();
    }

    /** Every expense of the site, newest first. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public List<ExpenseData> ofSite(UUID projectId) {
        return expenses.findByProjectIdOrderBySpentOnDescCreatedAtDesc(projectId).stream()
                .map(ExpenseService::data).toList();
    }

    @Transactional
    public ExpenseData create(SiteScope site, BigDecimal amount, LocalDate spentOn, String description,
                              ExpenseCategory category) {
        site.require(PermissionAction.MANAGE_BUDGET);
        return data(expenses.save(new Expense(site.site(), amount, spentOn, description, category)));
    }

    @Transactional
    public ExpenseData update(SiteScope site, UUID expenseId, BigDecimal amount, LocalDate spentOn,
                              String description, ExpenseCategory category) {
        Expense expense = editable(site, expenseId);
        expense.update(amount, spentOn, description, category);
        return data(expense);
    }

    @Transactional
    public void delete(SiteScope site, UUID expenseId) {
        expenses.delete(editable(site, expenseId));
    }

    @Transactional(readOnly = true)
    public List<ExpenseData> listForTask(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = forTask(site, goalId, activityId, PermissionAction.VIEW_BUDGET);
        return expenses.findByActivityIdOrderBySpentOnDescCreatedAtDesc(activity.getId()).stream()
                .map(ExpenseService::data).toList();
    }

    @Transactional
    public ExpenseData createForTask(SiteScope site, UUID goalId, UUID activityId, BigDecimal amount,
                                     LocalDate spentOn, String description, ExpenseCategory category) {
        Activity activity = forTask(site, goalId, activityId, PermissionAction.MANAGE_BUDGET);
        return data(expenses.save(new Expense(activity.getGoal().getProject(), activity, site.membership(), amount,
                spentOn, description, category)));
    }

    /** Overspending is allowed and shows as a negative {@code remaining}. */
    @Transactional(readOnly = true)
    public TaskBudget taskBudget(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = forTask(site, goalId, activityId, PermissionAction.VIEW_BUDGET);
        BigDecimal spent = expenses.sumOfActivity(activity.getId()).setScale(2);
        BigDecimal budget = activity.getAllocatedBudget();
        return new TaskBudget(budget, spent, budget == null ? null : budget.subtract(spent));
    }

    @Transactional(readOnly = true)
    public BudgetSummary summary(SiteScope site) {
        site.require(PermissionAction.VIEW_BUDGET);
        Project project = site.site();
        Map<ExpenseCategory, BigDecimal> totals = new EnumMap<>(ExpenseCategory.class);
        for (Object[] row : expenses.sumByCategory(project.getId())) {
            totals.put((ExpenseCategory) row[0], (BigDecimal) row[1]);
        }
        List<CategoryTotal> byCategory = Arrays.stream(ExpenseCategory.values())
                .map(c -> new CategoryTotal(c, totals.getOrDefault(c, BigDecimal.ZERO).setScale(2)))
                .toList();
        BigDecimal spent = byCategory.stream().map(CategoryTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal budget = project.getAllocatedBudget();
        BigDecimal toPhases = projects.allocatedToPhases(project.getId());
        return new BudgetSummary(budget, spent, budget == null ? null : budget.subtract(spent), toPhases,
                BudgetAllocation.unallocated(budget, toPhases), byCategory);
    }

    /** The task inside the site (404), then the budget permission or being its current Lead (403). */
    private Activity forTask(SiteScope site, UUID goalId, UUID activityId, PermissionAction action) {
        Activity activity = activities.load(site, goalId, activityId);
        if (!holds(site, action) && !activity.isLead(site.membershipId())) {
            throw new AccessDeniedException("Permission required");
        }
        return activity;
    }

    /**
     * Budget roles edit any expense of the site. Anyone else only an expense they recorded on a task they lead
     * now; every other case, including a missing expense, is the same 403 as before the Lead exception.
     */
    private Expense editable(SiteScope site, UUID expenseId) {
        if (holds(site, PermissionAction.MANAGE_BUDGET)) {
            site.require(PermissionAction.MANAGE_BUDGET);
            return load(site, expenseId);
        }
        UUID membershipId = site.membershipId();
        return expenses.findByIdAndProjectId(expenseId, site.siteId())
                .filter(e -> e.getActivity() != null && e.getActivity().isLead(membershipId)
                        && e.recordedBy(membershipId))
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
    }

    /** Budget permissions are organization-scope only, so holding one is enough. */
    private static boolean holds(SiteScope site, PermissionAction action) {
        return site.scopeOf(action).isPresent();
    }

    private Expense load(SiteScope site, UUID expenseId) {
        return expenses.findByIdAndProjectId(expenseId, site.siteId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
    }

    private static ExpenseData data(Expense e) {
        return new ExpenseData(e.getId(), e.getProject().getId(),
                e.getActivity() == null ? null : e.getActivity().getId(),
                e.getCreatedBy() == null ? null : e.getCreatedBy().getId(),
                e.getAmount(), e.getSpentOn(), e.getDescription(), e.getCategory());
    }
}

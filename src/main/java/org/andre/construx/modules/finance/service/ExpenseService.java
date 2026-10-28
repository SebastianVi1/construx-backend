package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.BudgetAllocation;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
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
 * Site expenses and the budget summary. Every call: active membership (404), tenant-filtered site (404),
 * then VIEW_BUDGET to read or MANAGE_BUDGET to write (403), then the expense inside that site (404).
 * One exception (ADR 0005): the current Lead of a task reads that task's expenses and budget, records expenses on
 * it, and edits or deletes the ones they recorded, without the budget permissions.
 */
@Service
public class ExpenseService {
    private final ExpenseRepository expenses;
    private final ProjectService projects;
    private final ActivityService activities;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ExpenseService(ExpenseRepository expenses, ProjectService projects, ActivityService activities,
                          MembershipGuard guard, DomainAuthorization authorization) {
        this.expenses = expenses;
        this.projects = projects;
        this.activities = activities;
        this.guard = guard;
        this.authorization = authorization;
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
    public List<ExpenseData> list(UUID userId, UUID organizationId, UUID projectId) {
        Project project = authorized(userId, organizationId, projectId, PermissionAction.VIEW_BUDGET);
        return expenses.findByProjectIdOrderBySpentOnDescCreatedAtDesc(project.getId()).stream()
                .map(ExpenseService::data).toList();
    }

    @Transactional
    public ExpenseData create(UUID userId, UUID organizationId, UUID projectId, BigDecimal amount, LocalDate spentOn,
                              String description, ExpenseCategory category) {
        Project project = authorized(userId, organizationId, projectId, PermissionAction.MANAGE_BUDGET);
        return data(expenses.save(new Expense(project, amount, spentOn, description, category)));
    }

    @Transactional
    public ExpenseData update(UUID userId, UUID organizationId, UUID projectId, UUID expenseId, BigDecimal amount,
                              LocalDate spentOn, String description, ExpenseCategory category) {
        Expense expense = editable(userId, organizationId, projectId, expenseId);
        expense.update(amount, spentOn, description, category);
        return data(expense);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID expenseId) {
        expenses.delete(editable(userId, organizationId, projectId, expenseId));
    }

    @Transactional(readOnly = true)
    public List<ExpenseData> listForTask(UUID userId, UUID organizationId, UUID projectId, UUID goalId,
                                         UUID activityId) {
        Activity activity = forTask(userId, organizationId, projectId, goalId, activityId,
                PermissionAction.VIEW_BUDGET).activity();
        return expenses.findByActivityIdOrderBySpentOnDescCreatedAtDesc(activity.getId()).stream()
                .map(ExpenseService::data).toList();
    }

    @Transactional
    public ExpenseData createForTask(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                                     BigDecimal amount, LocalDate spentOn, String description,
                                     ExpenseCategory category) {
        TaskCaller caller = forTask(userId, organizationId, projectId, goalId, activityId,
                PermissionAction.MANAGE_BUDGET);
        Activity activity = caller.activity();
        return data(expenses.save(new Expense(activity.getGoal().getProject(), activity, caller.member(), amount,
                spentOn, description, category)));
    }

    /** Overspending is allowed and shows as a negative {@code remaining}. */
    @Transactional(readOnly = true)
    public TaskBudget taskBudget(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId) {
        Activity activity = forTask(userId, organizationId, projectId, goalId, activityId,
                PermissionAction.VIEW_BUDGET).activity();
        BigDecimal spent = expenses.sumOfActivity(activity.getId()).setScale(2);
        BigDecimal budget = activity.getAllocatedBudget();
        return new TaskBudget(budget, spent, budget == null ? null : budget.subtract(spent));
    }

    @Transactional(readOnly = true)
    public BudgetSummary summary(UUID userId, UUID organizationId, UUID projectId) {
        Project project = authorized(userId, organizationId, projectId, PermissionAction.VIEW_BUDGET);
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

    private Project authorized(UUID userId, UUID organizationId, UUID projectId, PermissionAction action) {
        guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, action);
        return project;
    }

    private record TaskCaller(Activity activity, OrganizationMembership member) {}

    /** The task inside the tenant (404), then the budget permission or being its current Lead (403). */
    private TaskCaller forTask(UUID userId, UUID organizationId, UUID projectId, UUID goalId, UUID activityId,
                               PermissionAction action) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Activity activity = activities.load(organizationId, projectId, goalId, activityId);
        if (!holds(userId, organizationId, action) && !activity.isLead(member.getId())) {
            throw new AccessDeniedException("Permission required");
        }
        return new TaskCaller(activity, member);
    }

    /**
     * Budget roles edit any expense of the site. Anyone else only an expense they recorded on a task they lead
     * now; every other case, including a missing expense, is the same 403 as before the Lead exception.
     */
    private Expense editable(UUID userId, UUID organizationId, UUID projectId, UUID expenseId) {
        if (holds(userId, organizationId, PermissionAction.MANAGE_BUDGET)) {
            return load(authorized(userId, organizationId, projectId, PermissionAction.MANAGE_BUDGET), expenseId);
        }
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        return expenses.findByIdAndProjectId(expenseId, project.getId())
                .filter(e -> e.getActivity() != null && e.getActivity().isLead(member.getId())
                        && e.recordedBy(member.getId()))
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
    }

    /** Budget permissions are organization-scope only, so holding one is enough. */
    private boolean holds(UUID userId, UUID organizationId, PermissionAction action) {
        return authorization.scopeOf(userId, organizationId, action).isPresent();
    }

    private Expense load(Project project, UUID expenseId) {
        return expenses.findByIdAndProjectId(expenseId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
    }

    private static ExpenseData data(Expense e) {
        return new ExpenseData(e.getId(), e.getProject().getId(),
                e.getActivity() == null ? null : e.getActivity().getId(),
                e.getCreatedBy() == null ? null : e.getCreatedBy().getId(),
                e.getAmount(), e.getSpentOn(), e.getDescription(), e.getCategory());
    }
}

package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.BudgetAllocation;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
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
 */
@Service
public class ExpenseService {
    private final ExpenseRepository expenses;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ExpenseService(ExpenseRepository expenses, ProjectService projects, MembershipGuard guard,
                          DomainAuthorization authorization) {
        this.expenses = expenses;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record ExpenseData(UUID id, UUID projectId, BigDecimal amount, LocalDate spentOn, String description,
                              ExpenseCategory category) {}

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
        Project project = authorized(userId, organizationId, projectId, PermissionAction.MANAGE_BUDGET);
        Expense expense = load(project, expenseId);
        expense.update(amount, spentOn, description, category);
        return data(expense);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID expenseId) {
        Project project = authorized(userId, organizationId, projectId, PermissionAction.MANAGE_BUDGET);
        expenses.delete(load(project, expenseId));
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

    private Expense load(Project project, UUID expenseId) {
        return expenses.findByIdAndProjectId(expenseId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
    }

    private static ExpenseData data(Expense e) {
        return new ExpenseData(e.getId(), e.getProject().getId(), e.getAmount(), e.getSpentOn(), e.getDescription(),
                e.getCategory());
    }
}

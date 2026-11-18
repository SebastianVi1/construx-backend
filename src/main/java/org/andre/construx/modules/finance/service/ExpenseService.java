package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.ResourceDirectory;
import org.andre.construx.modules.pricing.service.ResourceDirectory.CatalogResource;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.BudgetAllocation;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ConflictException;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ResourceDirectory catalog;
    private final LaborCostService laborCost;

    public ExpenseService(ExpenseRepository expenses, ProjectService projects, ActivityService activities,
                          ResourceDirectory catalog, LaborCostService laborCost) {
        this.expenses = expenses;
        this.projects = projects;
        this.activities = activities;
        this.catalog = catalog;
        this.laborCost = laborCost;
    }

    /**
     * {@code activityId} and {@code createdByMembershipId} are null when the expense has no task or no recorder.
     * {@code resource}, {@code quantity} and {@code unitCost} are null unless the expense is from the Catalog;
     * {@code quantity} and {@code unitCost} are decimal strings. {@code unitCost} is also null for a caller without
     * VIEW_PRICES (docs/pricing.md §6.5): the amount, quantity and Resource stay visible, the Catalog cost does not.
     */
    public record ExpenseData(UUID id, UUID projectId, UUID activityId, UUID createdByMembershipId, BigDecimal amount,
                              LocalDate spentOn, String description, ExpenseCategory category,
                              ResourceRef resource, String quantity, String unitCost) {}

    public record ResourceRef(UUID id, String code, String name, ResourceType type, String unitCode) {}

    /**
     * A new expense: hand-typed ({@code amount} and {@code category}) or from the Catalog ({@code resourceId} and
     * {@code quantity}, docs/pricing.md §6.5). {@code description} may be null for one from the Catalog.
     */
    public record NewExpense(BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category,
                             UUID resourceId, BigDecimal quantity) {}

    /** Null keeps the stored value; {@code resourceOrQuantitySent} refuses an attempt to change them. */
    public record ExpenseChange(BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category,
                                boolean resourceOrQuantitySent) {}

    /** The task's own budget against what was charged to it; {@code remaining} is null without a budget. */
    public record TaskBudget(BigDecimal allocatedBudget, BigDecimal spent, BigDecimal remaining) {}

    public record CategoryTotal(ExpenseCategory category, BigDecimal amount) {}

    /**
     * {@code spent} is {@code recordedExpenses} plus {@code laborAccrued}, the Site's Accrued labor cost to date
     * (docs/pricing.md §6.4), which {@code byCategory} counts under LABOR. {@code remaining} is null without a
     * budget and negative on an overrun. {@code allocatedToPhases} is the sum of the phase budgets and
     * {@code unallocated} what the site has left to give phases, null without a budget.
     */
    public record BudgetSummary(BigDecimal allocatedBudget, BigDecimal recordedExpenses, BigDecimal laborAccrued,
                                BigDecimal spent, BigDecimal remaining, BigDecimal allocatedToPhases,
                                BigDecimal unallocated, List<CategoryTotal> byCategory) {}

    @Transactional(readOnly = true)
    public List<ExpenseData> list(SiteScope site) {
        site.require(PermissionAction.VIEW_BUDGET);
        return data(site, expenses.findByProjectIdOrderBySpentOnDescCreatedAtDesc(site.siteId()));
    }

    /** Every expense of the site, newest first. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public List<ExpenseData> ofSite(UUID projectId) {
        List<Expense> rows = expenses.findByProjectIdOrderBySpentOnDescCreatedAtDesc(projectId);
        return rows.isEmpty() ? List.of() : data(rows.getFirst().getProject().getOrganization().getId(), rows, true);
    }

    @Transactional
    public ExpenseData create(SiteScope site, NewExpense body) {
        site.require(PermissionAction.MANAGE_BUDGET);
        return save(site, site.site(), null, null, body);
    }

    @Transactional
    public ExpenseData update(SiteScope site, UUID expenseId, ExpenseChange change) {
        Expense expense = editable(site, expenseId);
        if (change.resourceOrQuantitySent()) {
            if (expense.isFromCatalog()) {
                throw Expense.catalogLocked();
            }
            throw new RuleViolationException("CATALOG_EXPENSE_FIELDS",
                    "A typed expense cannot be turned into an expense from the catalog");
        }
        expense.update(change.amount(), change.spentOn(), change.description(), change.category());
        return data(site, expense);
    }

    @Transactional
    public void delete(SiteScope site, UUID expenseId) {
        expenses.delete(editable(site, expenseId));
    }

    @Transactional(readOnly = true)
    public List<ExpenseData> listForTask(SiteScope site, UUID goalId, UUID activityId) {
        Activity activity = forTask(site, goalId, activityId, PermissionAction.VIEW_BUDGET);
        return data(site, expenses.findByActivityIdOrderBySpentOnDescCreatedAtDesc(activity.getId()));
    }

    @Transactional
    public ExpenseData createForTask(SiteScope site, UUID goalId, UUID activityId, NewExpense body) {
        Activity activity = forTask(site, goalId, activityId, PermissionAction.MANAGE_BUDGET);
        return save(site, activity.getGoal().getProject(), activity, site.membership(), body);
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
        BigDecimal recorded = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        BigDecimal accrued = laborCost.total(site.organizationId(), project.getId()).setScale(2);
        totals.merge(ExpenseCategory.LABOR, accrued, BigDecimal::add);
        List<CategoryTotal> byCategory = Arrays.stream(ExpenseCategory.values())
                .map(c -> new CategoryTotal(c, totals.getOrDefault(c, BigDecimal.ZERO).setScale(2)))
                .toList();
        BigDecimal spent = recorded.add(accrued);
        BigDecimal budget = project.getAllocatedBudget();
        BigDecimal toPhases = projects.allocatedToPhases(project.getId());
        return new BudgetSummary(budget, recorded, accrued, spent, budget == null ? null : budget.subtract(spent),
                toPhases, BudgetAllocation.unallocated(budget, toPhases), byCategory);
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

    /**
     * Hand-typed, or from the Catalog with the Resource's current cost frozen as its unit cost (§6.5). Whoever may
     * record the expense may charge the Catalog; only VIEW_PRICES holders see the unit cost in the answer.
     */
    private ExpenseData save(SiteScope site, Project project, Activity activity, OrganizationMembership createdBy,
                             NewExpense body) {
        if (body.resourceId() == null) {
            if (body.quantity() != null) {
                throw new RuleViolationException("CATALOG_EXPENSE_FIELDS", "A quantity needs a catalog resource");
            }
            return data(site, expenses.save(new Expense(project, activity, createdBy,
                    body.amount(), body.spentOn(), body.description(), body.category())));
        }
        if (body.amount() != null || body.category() != null) {
            throw new RuleViolationException("CATALOG_EXPENSE_FIELDS",
                    "An expense from the catalog computes its amount and category");
        }
        if (body.quantity() == null) {
            throw new IllegalArgumentException("Quantity is required");
        }
        CatalogResource resource = catalog.get(site.organizationId(), body.resourceId());
        ExpenseCategory category = categoryOf(resource.type());
        if (resource.archived()) {
            throw new ConflictException("ARCHIVED_RESOURCE", "The resource is archived");
        }
        if (resource.currentCost() == null) {
            throw new RuleViolationException("RESOURCE_WITHOUT_COST", "The resource has no cost yet");
        }
        String description = body.description() == null || body.description().isBlank()
                ? resource.name() : body.description();
        Expense expense = Expense.fromCatalog(project, activity, createdBy, resource.id(), body.quantity(),
                resource.currentCost(), body.spentOn(), description, category);
        return data(site, expenses.save(expense));
    }

    /** Materials, Equipment rates and Labor categories (or Gangs) can be charged; Work items cannot. */
    private static ExpenseCategory categoryOf(ResourceType type) {
        return switch (type) {
            case MATERIAL -> ExpenseCategory.MATERIALS;
            case EQUIPMENT -> ExpenseCategory.EQUIPMENT;
            case LABOR -> ExpenseCategory.LABOR;
            case WORK_ITEM -> throw new RuleViolationException("RESOURCE_NOT_EXPENSABLE",
                    "A work item cannot be charged as an expense");
        };
    }

    private ExpenseData data(SiteScope site, Expense e) {
        return data(site, List.of(e)).getFirst();
    }

    private List<ExpenseData> data(SiteScope site, List<Expense> rows) {
        return data(site.organizationId(), rows, site.can(PermissionAction.VIEW_PRICES));
    }

    /** Loads the Catalog Resources of the expenses in one organization-filtered query. */
    private List<ExpenseData> data(UUID organizationId, List<Expense> rows, boolean showUnitCost) {
        Set<UUID> resourceIds = rows.stream().map(Expense::getResourceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, CatalogResource> resources = catalog.byIds(organizationId, resourceIds);
        return rows.stream().map(e -> data(e, e.isFromCatalog() ? resources.get(e.getResourceId()) : null,
                showUnitCost)).toList();
    }

    private static ExpenseData data(Expense e, CatalogResource resource, boolean showUnitCost) {
        return new ExpenseData(e.getId(), e.getProject().getId(),
                e.getActivity() == null ? null : e.getActivity().getId(),
                e.getCreatedBy() == null ? null : e.getCreatedBy().getId(),
                e.getAmount(), e.getSpentOn(), e.getDescription(), e.getCategory(),
                resource == null ? null : new ResourceRef(resource.id(), resource.code(), resource.name(),
                        resource.type(), resource.unitCode()),
                e.getQuantity() == null ? null : e.getQuantity().stripTrailingZeros().toPlainString(),
                e.getUnitCost() == null || !showUnitCost ? null : e.getUnitCost().setScale(2).toPlainString());
    }
}

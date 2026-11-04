package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.model.SiteBudgetStatus;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-wide finance summary and monthly expense series.
 * <p>
 * Check order on every call:
 * <ol>
 *   <li>MembershipGuard.requireActive — 404 for non-members and unknown organizations.</li>
 *   <li>DomainAuthorization.require(VIEW_BUDGET, projectId=null) — 403 for roles without it.</li>
 *   <li>Parameter validation (range checks) — 400.</li>
 * </ol>
 */
@Service
public class OrgFinanceService {

    private final ExpenseRepository expenses;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public OrgFinanceService(ExpenseRepository expenses, ProjectService projects,
                             MembershipGuard guard, DomainAuthorization authorization) {
        this.expenses = expenses;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    // ── Response records ──────────────────────────────────────────────────────

    public record SiteFinance(UUID siteId, String siteName, BigDecimal allocatedBudget,
                              BigDecimal spent, BigDecimal remaining, BigDecimal executionPct,
                              SiteBudgetStatus status) {}

    public record OrgFinanceSummary(BigDecimal totalBudget, BigDecimal totalSpent,
                                    BigDecimal totalRemaining, BigDecimal executionPct,
                                    int sitesOverBudget, int sitesNearLimit,
                                    List<ExpenseService.CategoryTotal> byCategory,
                                    List<SiteFinance> sites) {}

    public record MonthlySpend(String month, BigDecimal spent) {}

    public record MonthlyExpenseSummary(BigDecimal totalBudget, List<MonthlySpend> months) {}

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrgFinanceSummary summary(UUID userId, UUID organizationId) {
        checkAccess(userId, organizationId);

        List<Project> allSites = projects.inOrganization(organizationId);

        // Build spent-by-project map (covers all sites, budget or not)
        Map<UUID, BigDecimal> spentByProject = new HashMap<>();
        for (Object[] row : expenses.sumByProjectInOrganization(organizationId)) {
            spentByProject.put((UUID) row[0], (BigDecimal) row[1]);
        }

        // Build by-category map (budgeted + non-archived only)
        Map<ExpenseCategory, BigDecimal> categoryTotals = new EnumMap<>(ExpenseCategory.class);
        for (Object[] row : expenses.sumByCategoryForBudgetedSites(organizationId)) {
            categoryTotals.put((ExpenseCategory) row[0], (BigDecimal) row[1]);
        }

        // Company-level totals (budgeted sites only)
        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;

        // Per-site breakdown (all included sites, ordered by name from query)
        List<SiteFinance> siteList = allSites.stream().map(site -> {
            BigDecimal budget = site.getAllocatedBudget();
            BigDecimal spent = spentByProject.getOrDefault(site.getId(), BigDecimal.ZERO).setScale(2);
            BigDecimal remaining = budget == null ? null : budget.subtract(spent).setScale(2);
            BigDecimal pct = executionPct(budget, spent);
            SiteBudgetStatus status = SiteBudgetStatus.of(budget, spent);
            return new SiteFinance(site.getId(), site.getName(),
                    budget == null ? null : budget.setScale(2),
                    spent, remaining, pct, status);
        }).toList();

        // Accumulate company totals from budgeted sites
        for (SiteFinance sf : siteList) {
            if (sf.allocatedBudget() != null) {
                totalBudget = totalBudget.add(sf.allocatedBudget());
                totalSpent = totalSpent.add(sf.spent());
            }
        }
        totalBudget = totalBudget.setScale(2);
        totalSpent = totalSpent.setScale(2);

        BigDecimal totalRemaining = totalBudget.subtract(totalSpent).setScale(2);
        BigDecimal companyPct = executionPct(totalBudget, totalSpent);

        int overBudget = (int) siteList.stream().filter(s -> s.status() == SiteBudgetStatus.OVER_BUDGET).count();
        int nearLimit  = (int) siteList.stream().filter(s -> s.status() == SiteBudgetStatus.NEAR_LIMIT).count();

        // Always emit all four categories in enum order, 0.00 for unused
        List<ExpenseService.CategoryTotal> byCategory = Arrays.stream(ExpenseCategory.values())
                .map(c -> new ExpenseService.CategoryTotal(c,
                        categoryTotals.getOrDefault(c, BigDecimal.ZERO).setScale(2)))
                .toList();

        return new OrgFinanceSummary(totalBudget, totalSpent, totalRemaining, companyPct,
                overBudget, nearLimit, byCategory, siteList);
    }

    @Transactional(readOnly = true)
    public MonthlyExpenseSummary monthlyExpenses(UUID userId, UUID organizationId,
                                                 LocalDate from, LocalDate to) {
        checkAccess(userId, organizationId);

        // Apply defaults after auth checks, before validation
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfYear(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        YearMonth ymFrom = YearMonth.from(effectiveFrom);
        YearMonth ymTo   = YearMonth.from(effectiveTo);
        long monthsBetween = ymFrom.until(ymTo, ChronoUnit.MONTHS);
        if (monthsBetween > 36) {
            throw new IllegalArgumentException("Date range must not exceed 36 months");
        }

        // Company-level totalBudget (same calculation as summary)
        BigDecimal totalBudget = projects.inOrganization(organizationId).stream()
                .filter(p -> p.getAllocatedBudget() != null)
                .map(Project::getAllocatedBudget)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);

        // Build a year-month → spent map from the DB
        Map<YearMonth, BigDecimal> monthMap = new HashMap<>();
        for (Object[] row : expenses.sumByMonthForBudgetedSites(organizationId, effectiveFrom, effectiveTo)) {
            int year  = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            BigDecimal amount = (BigDecimal) row[2];
            monthMap.put(YearMonth.of(year, month), amount);
        }

        // Fill all calendar months in range, including gaps as 0.00
        List<MonthlySpend> result = new ArrayList<>();
        YearMonth cursor = ymFrom;
        while (!cursor.isAfter(ymTo)) {
            result.add(new MonthlySpend(cursor.toString(),
                    monthMap.getOrDefault(cursor, BigDecimal.ZERO).setScale(2)));
            cursor = cursor.plusMonths(1);
        }

        return new MonthlyExpenseSummary(totalBudget, result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void checkAccess(UUID userId, UUID organizationId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_BUDGET);
    }

    /**
     * Execution % = spent / budget * 100, rounded half-up to 1 decimal.
     * Returns null when budget is null or zero (no division by zero).
     */
    private static BigDecimal executionPct(BigDecimal budget, BigDecimal spent) {
        if (budget == null || budget.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return spent.multiply(BigDecimal.valueOf(100))
                .divide(budget, 1, RoundingMode.HALF_UP);
    }
}

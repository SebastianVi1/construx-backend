package org.andre.construx.modules.finance.model;

import java.math.BigDecimal;

/**
 * Budget health of a site. Computed from the site's allocatedBudget and total spent.
 * Use the static factory {@code of(allocatedBudget, spent)}.
 */
public enum SiteBudgetStatus {
    OK,
    NEAR_LIMIT,
    OVER_BUDGET,
    NO_BUDGET;

    /**
     * Rules (all comparisons are exact BigDecimal, no rounding):
     * <ul>
     *   <li>{@code NO_BUDGET}   — allocatedBudget is null</li>
     *   <li>{@code OVER_BUDGET} — spent &gt; allocatedBudget</li>
     *   <li>{@code NEAR_LIMIT}  — not over, and spent*100 &gt;= allocatedBudget*80 (80 % up to and including 100 %)</li>
     *   <li>{@code OK}          — otherwise</li>
     * </ul>
     * A zero budget with zero spent is {@code OK}; a zero budget with any spending is {@code OVER_BUDGET}.
     */
    public static SiteBudgetStatus of(BigDecimal allocatedBudget, BigDecimal spent) {
        if (allocatedBudget == null) {
            return NO_BUDGET;
        }
        if (spent.compareTo(allocatedBudget) > 0) {
            return OVER_BUDGET;
        }
        // Zero budget with zero spent is explicitly OK (0*100 >= 0*80 would give NEAR_LIMIT otherwise).
        if (allocatedBudget.compareTo(BigDecimal.ZERO) == 0) {
            return OK;
        }
        // spent * 100 >= allocatedBudget * 80
        if (spent.multiply(BigDecimal.valueOf(100)).compareTo(allocatedBudget.multiply(BigDecimal.valueOf(80))) >= 0) {
            return NEAR_LIMIT;
        }
        return OK;
    }
}

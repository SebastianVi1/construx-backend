package org.andre.construx.modules.finance.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Boundary unit test for SiteBudgetStatus.of(allocatedBudget, spent). */
class SiteBudgetStatusTest {

    @Test
    void nullBudgetIsNoBudget() {
        assertEquals(SiteBudgetStatus.NO_BUDGET, SiteBudgetStatus.of(null, bd("0")));
        assertEquals(SiteBudgetStatus.NO_BUDGET, SiteBudgetStatus.of(null, bd("100")));
    }

    @Test
    void zeroBudgetZeroSpentIsOk() {
        assertEquals(SiteBudgetStatus.OK, SiteBudgetStatus.of(bd("0"), bd("0")));
    }

    @Test
    void zeroBudgetAnySpendingIsOverBudget() {
        assertEquals(SiteBudgetStatus.OVER_BUDGET, SiteBudgetStatus.of(bd("0"), bd("0.01")));
        assertEquals(SiteBudgetStatus.OVER_BUDGET, SiteBudgetStatus.of(bd("0"), bd("1")));
    }

    @Test
    void spentExceedsBudgetIsOverBudget() {
        // 100.01% — just over
        assertEquals(SiteBudgetStatus.OVER_BUDGET, SiteBudgetStatus.of(bd("100"), bd("100.01")));
        assertEquals(SiteBudgetStatus.OVER_BUDGET, SiteBudgetStatus.of(bd("1000"), bd("1500")));
    }

    @Test
    void exactlyHundredPercentIsNearLimit() {
        // spent == budget → not over, and 100 * 100 >= 100 * 80 → NEAR_LIMIT
        assertEquals(SiteBudgetStatus.NEAR_LIMIT, SiteBudgetStatus.of(bd("100"), bd("100")));
        assertEquals(SiteBudgetStatus.NEAR_LIMIT, SiteBudgetStatus.of(bd("200000"), bd("200000")));
    }

    @Test
    void exactlyEightyPercentIsNearLimit() {
        // spent * 100 == budget * 80 boundary
        assertEquals(SiteBudgetStatus.NEAR_LIMIT, SiteBudgetStatus.of(bd("100"), bd("80")));
        assertEquals(SiteBudgetStatus.NEAR_LIMIT, SiteBudgetStatus.of(bd("1000"), bd("800")));
    }

    @Test
    void justBelowEightyPercentIsOk() {
        // 79.99% — below the threshold
        assertEquals(SiteBudgetStatus.OK, SiteBudgetStatus.of(bd("10000"), bd("7999")));
    }

    @Test
    void wellBelowEightyPercentIsOk() {
        assertEquals(SiteBudgetStatus.OK, SiteBudgetStatus.of(bd("100"), bd("0")));
        assertEquals(SiteBudgetStatus.OK, SiteBudgetStatus.of(bd("100"), bd("50")));
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}

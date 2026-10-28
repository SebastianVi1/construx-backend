package org.andre.construx.shared.validation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyAmountTest {
    @Test
    void nonNegativeAcceptsZeroAndScalesToTwoDecimals() {
        assertEquals(new BigDecimal("0.00"), MoneyAmount.nonNegative(BigDecimal.ZERO, "Budget"));
        assertEquals(new BigDecimal("12.50"), MoneyAmount.nonNegative(new BigDecimal("12.5"), "Budget"));
        assertEquals(new BigDecimal("3.00"), MoneyAmount.nonNegative(new BigDecimal("3.000"), "Budget"));
    }

    @Test
    void nonNegativeRejectsNegativesExtraDecimalsAndOverflow() {
        assertEquals("Budget cannot be negative", assertThrows(IllegalArgumentException.class,
                () -> MoneyAmount.nonNegative(new BigDecimal("-0.01"), "Budget")).getMessage());
        assertEquals("Budget allows at most 2 decimals", assertThrows(IllegalArgumentException.class,
                () -> MoneyAmount.nonNegative(new BigDecimal("1.234"), "Budget")).getMessage());
        assertEquals("Budget is too large", assertThrows(IllegalArgumentException.class,
                () -> MoneyAmount.nonNegative(new BigDecimal("1000000000000"), "Budget")).getMessage());
        assertEquals(new BigDecimal("999999999999.99"),
                MoneyAmount.nonNegative(new BigDecimal("999999999999.99"), "Budget"));
    }

    @Test
    void positiveRejectsZeroAndNull() {
        assertEquals("Amount must be greater than 0", assertThrows(IllegalArgumentException.class,
                () -> MoneyAmount.positive(BigDecimal.ZERO, "Amount")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> MoneyAmount.positive(null, "Amount"));
        assertEquals(new BigDecimal("0.01"), MoneyAmount.positive(new BigDecimal("0.01"), "Amount"));
    }
}

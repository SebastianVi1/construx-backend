package org.andre.construx.shared.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts stored as numeric(14,2). {@code label} starts the error message, e.g. "Allocated budget". */
public final class MoneyAmount {
    private MoneyAmount() {
    }

    /** Zero or more, at most 2 decimals; returned at scale 2. */
    public static BigDecimal nonNegative(BigDecimal amount, String label) {
        DomainValidation.required(amount, label + " is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(label + " cannot be negative");
        }
        return scaled(amount, label);
    }

    /** Greater than zero, at most 2 decimals; returned at scale 2. */
    public static BigDecimal positive(BigDecimal amount, String label) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(label + " must be greater than 0");
        }
        return scaled(amount, label);
    }

    private static BigDecimal scaled(BigDecimal amount, String label) {
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(label + " allows at most 2 decimals");
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (scaled.precision() > 14) {
            throw new IllegalArgumentException(label + " is too large");
        }
        return scaled;
    }
}

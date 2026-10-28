package org.andre.construx.shared.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Body of every {@code PUT .../budget}. {@code allocatedBudget} is required but may be null, which clears
 * the budget. A body without it is rejected so a client that forgets the field cannot wipe the value; the
 * setter records that it was sent.
 */
public final class BudgetUpdate {
    @PositiveOrZero @Digits(integer = 12, fraction = 2)
    private BigDecimal allocatedBudget;
    private boolean sent;

    @JsonProperty("allocatedBudget")
    public void setAllocatedBudget(BigDecimal allocatedBudget) {
        this.allocatedBudget = allocatedBudget;
        this.sent = true;
    }

    public BigDecimal requireAllocatedBudget() {
        if (!sent) {
            throw new IllegalArgumentException("allocatedBudget is required; send null to clear it");
        }
        return allocatedBudget;
    }
}

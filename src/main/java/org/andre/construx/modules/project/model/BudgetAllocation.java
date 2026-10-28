package org.andre.construx.modules.project.model;

import org.andre.construx.shared.exception.RuleViolationException;

import java.math.BigDecimal;

/**
 * Budgets nest: phases are carved out of the site budget and tasks out of their phase budget. The same rule
 * holds at every level, so the parent never promises more than it has.
 */
public final class BudgetAllocation {
    public static final String EXCEEDED = "BUDGET_EXCEEDED";
    public static final String PARENT_MISSING = "BUDGET_PARENT_MISSING";

    private BudgetAllocation() {
    }

    /** A child budget must fit in what the parent has left after its siblings; null always fits. */
    public static void checkChild(BigDecimal parentBudget, BigDecimal allocatedToSiblings, BigDecimal childBudget) {
        if (childBudget == null) {
            return;
        }
        if (parentBudget == null) {
            throw new RuleViolationException(PARENT_MISSING, "Set the parent budget before allocating from it");
        }
        if (allocatedToSiblings.add(childBudget).compareTo(parentBudget) > 0) {
            throw new RuleViolationException(EXCEEDED, "Allocated budget exceeds what the parent has left");
        }
    }

    /** A parent budget cannot drop below, or be cleared under, what its children already hold. */
    public static void checkParent(BigDecimal parentBudget, BigDecimal allocatedToChildren) {
        if (allocatedToChildren.signum() == 0) {
            return;
        }
        if (parentBudget == null || parentBudget.compareTo(allocatedToChildren) < 0) {
            throw new RuleViolationException(EXCEEDED, "Budget cannot be lower than what is already allocated");
        }
    }

    /** What the parent still has to give; null when it has no budget. */
    public static BigDecimal unallocated(BigDecimal parentBudget, BigDecimal allocatedToChildren) {
        return parentBudget == null ? null : parentBudget.subtract(allocatedToChildren);
    }
}

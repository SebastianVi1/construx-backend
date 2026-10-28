package org.andre.construx.modules.finance.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.project.model.Project;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseTest {
    private final Project project = new Project(
            new Organization("Acme", new UserAccount("o@acme.com", "hash")), "Tower");
    private final LocalDate day = LocalDate.of(2026, 10, 1);

    @Test
    void expenseKeepsTwoDecimalsAndATrimmedDescription() {
        Expense expense = new Expense(project, new BigDecimal("1200.5"), day, "  Cement bags  ", ExpenseCategory.MATERIALS);
        assertEquals(new BigDecimal("1200.50"), expense.getAmount());
        assertEquals("Cement bags", expense.getDescription());
        assertEquals(ExpenseCategory.MATERIALS, expense.getCategory());
        assertEquals(day, expense.getSpentOn());
    }

    @Test
    void amountMustBePositiveWithTwoDecimalsAndFitTheColumn() {
        for (String bad : new String[] {"0", "-1", "10.001", "1000000000000"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Expense(project, new BigDecimal(bad), day, "x", ExpenseCategory.OTHER), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new Expense(project, null, day, "x", ExpenseCategory.OTHER));
        assertDoesNotThrow(() -> new Expense(project, new BigDecimal("999999999999.99"), day, "x", ExpenseCategory.OTHER));
    }

    @Test
    void descriptionDateAndCategoryAreRequired() {
        BigDecimal one = BigDecimal.ONE;
        assertThrows(IllegalArgumentException.class, () -> new Expense(project, one, day, "  ", ExpenseCategory.OTHER));
        assertThrows(IllegalArgumentException.class, () -> new Expense(project, one, day, "x".repeat(161), ExpenseCategory.OTHER));
        assertThrows(IllegalArgumentException.class, () -> new Expense(project, one, null, "x", ExpenseCategory.OTHER));
        assertThrows(IllegalArgumentException.class, () -> new Expense(project, one, day, "x", null));
    }

    @Test
    void updateChangesOnlyTheGivenFields() {
        Expense expense = new Expense(project, BigDecimal.TEN, day, "Rent", ExpenseCategory.EQUIPMENT);
        expense.update(null, null, null, ExpenseCategory.OTHER);
        assertEquals(new BigDecimal("10.00"), expense.getAmount());
        assertEquals("Rent", expense.getDescription());
        assertEquals(ExpenseCategory.OTHER, expense.getCategory());
        expense.update(new BigDecimal("20"), day.plusDays(1), "Crane rent", null);
        assertEquals(new BigDecimal("20.00"), expense.getAmount());
        assertEquals(day.plusDays(1), expense.getSpentOn());
        assertEquals("Crane rent", expense.getDescription());
        assertThrows(IllegalArgumentException.class, () -> expense.update(BigDecimal.ZERO, null, null, null));
    }
}

package org.andre.construx.modules.finance.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.service.ExpenseService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}")
public class ExpenseController {
    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    public record ExpenseCreate(@NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                @NotNull LocalDate spentOn, @NotBlank @Size(max = 160) String description,
                                @NotNull ExpenseCategory category) {}

    /** Every field optional; a missing field keeps the stored value. */
    public record ExpensePatch(@Positive @Digits(integer = 12, fraction = 2) BigDecimal amount, LocalDate spentOn,
                               @Size(max = 160) String description, ExpenseCategory category) {}

    /** Allocated budget, total spent, what remains and the spend per category. */
    @GetMapping("/budget")
    public ExpenseService.BudgetSummary budget(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                               Authentication authentication) {
        return expenses.summary(userId(authentication), organizationId, projectId);
    }

    @GetMapping("/expenses")
    public List<ExpenseService.ExpenseData> list(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                                 Authentication authentication) {
        return expenses.list(userId(authentication), organizationId, projectId);
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.ExpenseData create(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                             @Valid @RequestBody ExpenseCreate body, Authentication authentication) {
        return expenses.create(userId(authentication), organizationId, projectId, body.amount(), body.spentOn(),
                body.description(), body.category());
    }

    @PatchMapping("/expenses/{expenseId}")
    public ExpenseService.ExpenseData update(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                             @PathVariable UUID expenseId, @Valid @RequestBody ExpensePatch body,
                                             Authentication authentication) {
        return expenses.update(userId(authentication), organizationId, projectId, expenseId, body.amount(),
                body.spentOn(), body.description(), body.category());
    }

    @DeleteMapping("/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID expenseId,
                       Authentication authentication) {
        expenses.delete(userId(authentication), organizationId, projectId, expenseId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

package org.andre.construx.modules.finance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.validation.DomainValidation;
import org.andre.construx.shared.validation.MoneyAmount;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money spent on a site; the sum of a site's expenses is subtracted from its allocated budget. */
@Entity
@Table(name = "expense")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @NotNull
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Column(nullable = false)
    private LocalDate spentOn;

    @NotNull
    @Column(nullable = false, length = 160)
    private String description;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpenseCategory category;

    @NotNull
    @Column(nullable = false)
    private Instant createdAt;

    public Expense(Project project, BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category) {
        this.project = DomainValidation.required(project, "Project is required");
        this.amount = checkedAmount(amount);
        this.spentOn = DomainValidation.required(spentOn, "Expense date is required");
        this.description = checkedDescription(description);
        this.category = DomainValidation.required(category, "Expense category is required");
        this.createdAt = Instant.now();
    }

    /** Null arguments keep the stored value. */
    public void update(BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category) {
        BigDecimal newAmount = amount != null ? checkedAmount(amount) : this.amount;
        String newDescription = description != null ? checkedDescription(description) : this.description;
        this.amount = newAmount;
        this.description = newDescription;
        if (spentOn != null) this.spentOn = spentOn;
        if (category != null) this.category = category;
    }

    private static BigDecimal checkedAmount(BigDecimal amount) {
        return MoneyAmount.positive(amount, "Expense amount");
    }

    private static String checkedDescription(String description) {
        if (description == null || description.isBlank() || description.strip().length() > 160) {
            throw new IllegalArgumentException("Expense description must have 1 to 160 characters");
        }
        return description.strip();
    }
}

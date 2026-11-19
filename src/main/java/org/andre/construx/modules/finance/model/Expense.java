package org.andre.construx.modules.finance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.validation.DomainValidation;
import org.andre.construx.shared.validation.MoneyAmount;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Money spent on a site; the sum of a site's expenses is subtracted from its allocated budget. An expense may be
 * charged to one task of the site, and remembers who recorded it (null for expenses recorded before V18).
 */
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_membership_id")
    private OrganizationMembership createdBy;

    public Expense(Project project, BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category) {
        this(project, null, null, amount, spentOn, description, category);
    }

    /** {@code activity} is optional and must belong to {@code project}. */
    public Expense(Project project, Activity activity, OrganizationMembership createdBy, BigDecimal amount,
                   LocalDate spentOn, String description, ExpenseCategory category) {
        this.project = DomainValidation.required(project, "Project is required");
        if (activity != null && !project.sameAs(activity.getGoal().getProject())) {
            throw new IllegalArgumentException("The task belongs to another site");
        }
        this.activity = activity;
        this.createdBy = createdBy;
        this.amount = checkedAmount(amount);
        this.spentOn = DomainValidation.required(spentOn, "Expense date is required");
        this.description = checkedDescription(description);
        this.category = DomainValidation.required(category, "Expense category is required");
        this.createdAt = Instant.now();
    }

    /** Whether {@code membershipId} recorded this expense. */
    public boolean recordedBy(UUID membershipId) {
        return createdBy != null && membershipId != null && membershipId.equals(createdBy.getId());
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

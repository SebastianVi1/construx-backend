package org.andre.construx.modules.finance.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Activity;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.validation.DomainValidation;
import org.andre.construx.shared.validation.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** The Catalog Resource of an expense from the Catalog; null for a hand-typed expense. */
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(precision = 18, scale = 6)
    private BigDecimal quantity;

    /** The Resource's cost when the expense was recorded; never follows later Catalog changes. */
    @Column(name = "unit_cost", precision = 14, scale = 2)
    private BigDecimal unitCost;

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

    /**
     * An expense from the Catalog (docs/pricing.md §6.5): {@code amount = round2(quantity × unitCost)}, which must
     * still be greater than zero. The Resource, quantity and unit cost are frozen.
     */
    public static Expense fromCatalog(Project project, Activity activity, OrganizationMembership createdBy,
                                      UUID resourceId, BigDecimal quantity, BigDecimal unitCost, LocalDate spentOn,
                                      String description, ExpenseCategory category) {
        DomainValidation.required(resourceId, "Resource is required");
        BigDecimal q = checkedQuantity(quantity);
        BigDecimal cost = MoneyAmount.nonNegative(unitCost, "Unit cost");
        Expense expense = new Expense(project, activity, createdBy, q.multiply(cost).setScale(2, RoundingMode.HALF_UP),
                spentOn, description, category);
        expense.resourceId = resourceId;
        expense.quantity = q;
        expense.unitCost = cost;
        return expense;
    }

    public boolean isFromCatalog() {
        return resourceId != null;
    }

    /** Whether {@code membershipId} recorded this expense. */
    public boolean recordedBy(UUID membershipId) {
        return createdBy != null && membershipId != null && membershipId.equals(createdBy.getId());
    }

    /** Null arguments keep the stored value. An expense from the Catalog keeps its amount and category. */
    public void update(BigDecimal amount, LocalDate spentOn, String description, ExpenseCategory category) {
        if (isFromCatalog() && (amount != null || category != null)) {
            throw catalogLocked();
        }
        BigDecimal newAmount = amount != null ? checkedAmount(amount) : this.amount;
        String newDescription = description != null ? checkedDescription(description) : this.description;
        this.amount = newAmount;
        this.description = newDescription;
        if (spentOn != null) this.spentOn = spentOn;
        if (category != null) this.category = category;
    }

    public static RuleViolationException catalogLocked() {
        return new RuleViolationException("CATALOG_EXPENSE_LOCKED",
                "An expense from the catalog only allows changing its date and description");
    }

    /** Greater than zero, at most 6 decimals and 12 integer digits (numeric(18,6)). */
    private static BigDecimal checkedQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }
        if (quantity.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("Quantity allows at most 6 decimals");
        }
        BigDecimal scaled = quantity.setScale(6, RoundingMode.UNNECESSARY);
        if (scaled.precision() > 18) {
            throw new IllegalArgumentException("Quantity is too large");
        }
        return scaled;
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

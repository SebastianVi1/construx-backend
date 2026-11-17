package org.andre.construx.modules.project.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.shared.validation.DomainValidation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable row of a person's Labor rate history on a Site (docs/pricing.md §1.12). Keyed by membership, not by
 * Site access, so removing someone from the Site keeps it. The current rate is the latest row.
 */
@Entity
@Table(name = "labor_rate_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LaborRateAssignment {
    private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("10000000000");

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private LaborRateKind kind;

    @Column(name = "labor_category_resource_id", updatable = false)
    private UUID laborCategoryResourceId;

    @Column(name = "daily_amount", precision = 12, scale = 2, updatable = false)
    private BigDecimal dailyAmount;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "set_by_membership_id", nullable = false, updatable = false)
    private UUID setByMembershipId;

    private LaborRateAssignment(UUID organizationId, UUID projectId, UUID membershipId, LaborRateKind kind,
                                UUID laborCategoryResourceId, BigDecimal dailyAmount, Instant effectiveFrom,
                                UUID setByMembershipId) {
        this.id = UUID.randomUUID();
        this.organizationId = DomainValidation.required(organizationId, "Organization is required");
        this.projectId = DomainValidation.required(projectId, "Site is required");
        this.membershipId = DomainValidation.required(membershipId, "Member is required");
        this.kind = kind;
        this.laborCategoryResourceId = laborCategoryResourceId;
        this.dailyAmount = dailyAmount;
        this.effectiveFrom = DomainValidation.required(effectiveFrom, "Effective time is required")
                .truncatedTo(ChronoUnit.MICROS);
        this.setByMembershipId = DomainValidation.required(setByMembershipId, "Who sets the rate is required");
    }

    /** The rate a person gets: a catalog category, a fixed daily amount, or none. */
    public record Choice(LaborRateKind kind, UUID laborCategoryId, BigDecimal dailyAmount) {
        public Choice {
            if (kind == null) {
                throw new IllegalArgumentException("Rate kind is required");
            }
            switch (kind) {
                case CATALOG -> {
                    if (laborCategoryId == null) {
                        throw new IllegalArgumentException("A catalog rate needs a labor category");
                    }
                    dailyAmount = null;
                }
                case FIXED -> {
                    dailyAmount = fixedAmount(dailyAmount);
                    laborCategoryId = null;
                }
                case NONE -> {
                    laborCategoryId = null;
                    dailyAmount = null;
                }
            }
        }

        private static BigDecimal fixedAmount(BigDecimal amount) {
            if (amount == null) {
                throw new IllegalArgumentException("A fixed rate needs a daily amount");
            }
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("Daily amount cannot be negative");
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Daily amount allows at most 2 decimals");
            }
            BigDecimal scaled = amount.setScale(2);
            if (scaled.compareTo(AMOUNT_LIMIT) >= 0) {
                throw new IllegalArgumentException("Daily amount is too large");
            }
            return scaled;
        }
    }

    /**
     * A new row effective {@code at}, or strictly after {@code previous} so the history keeps its order when two
     * writes land in the same microsecond.
     */
    public static LaborRateAssignment of(UUID organizationId, UUID projectId, UUID membershipId, Choice choice,
                                         Instant at, LaborRateAssignment previous, UUID setByMembershipId) {
        Instant effective = at.truncatedTo(ChronoUnit.MICROS);
        if (previous != null && !effective.isAfter(previous.effectiveFrom)) {
            effective = previous.effectiveFrom.plus(1, ChronoUnit.MICROS);
        }
        return new LaborRateAssignment(organizationId, projectId, membershipId, choice.kind(),
                choice.laborCategoryId(), choice.dailyAmount(), effective, setByMembershipId);
    }

    /** Same kind, category and amount: setting it again writes nothing. */
    public boolean sameAs(Choice choice) {
        return kind == choice.kind() && Objects.equals(laborCategoryResourceId, choice.laborCategoryId())
                && (dailyAmount == null ? choice.dailyAmount() == null
                : choice.dailyAmount() != null && dailyAmount.compareTo(choice.dailyAmount()) == 0);
    }
}

package org.andre.construx.modules.pricing.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.shared.validation.DomainValidation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One Cost of a simple Resource from {@code effectiveFrom} until the next entry (ADR 0009). Immutable: entries are
 * never changed, deleted or backdated; create them through {@link Resource#recordCost}.
 */
@Entity
@Table(name = "resource_cost_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResourceCostHistory {
    public static final int SKU_MAX = 64;
    public static final int NOTE_MAX = 160;
    /** numeric(14,2) holds at most twelve integer digits. */
    private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("1000000000000");

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CostSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_membership_id")
    private OrganizationMembership recordedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(length = SKU_MAX)
    private String sku;

    @Column(length = NOTE_MAX)
    private String note;

    ResourceCostHistory(Resource resource, BigDecimal amount, CostSource source, OrganizationMembership recordedBy,
                        Supplier supplier, String sku, String note, Instant effectiveFrom) {
        this.resource = DomainValidation.required(resource, "Resource is required");
        this.organization = resource.getOrganization();
        this.amount = checkAmount(amount);
        this.source = DomainValidation.required(source, "Source is required");
        this.recordedBy = recordedBy;
        this.supplier = supplier;
        this.sku = PricingText.optional(sku, SKU_MAX, "SKU", "sku");
        this.note = PricingText.optional(note, NOTE_MAX, "Note", "note");
        this.effectiveFrom = DomainValidation.required(effectiveFrom, "Effective time is required");
    }

    /** Zero is allowed (a free Resource or a percentage placeholder); at most two decimals. */
    public static BigDecimal checkAmount(BigDecimal value) {
        if (value == null) {
            throw new PricingRuleException("amount", "REQUIRED", "Cost is required");
        }
        if (value.signum() < 0 || value.compareTo(AMOUNT_LIMIT) >= 0) {
            throw new PricingRuleException("amount", "INVALID", "Cost must be at least zero and below 10^12");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new PricingRuleException("amount", "TOO_MANY_DECIMALS", "Cost must have at most two decimals");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}

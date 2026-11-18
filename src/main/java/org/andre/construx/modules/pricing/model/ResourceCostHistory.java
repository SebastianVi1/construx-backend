package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One immutable cost of a simple Resource from {@code effectiveFrom} on (ADR 0009); never updated or deleted. */
@Entity
@Table(name = "resource_cost_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResourceCostHistory {
    public static final int SKU_MAX = 64;
    public static final int NOTE_MAX = 160;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CostSource source;

    @Column(name = "recorded_by_membership_id")
    private UUID recordedByMembershipId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(length = 64)
    private String sku;

    @Column(length = 160)
    private String note;

    /** The Catalog import that wrote this entry, null for hand-made ones. */
    @Column(name = "import_id")
    private UUID importId;

    ResourceCostHistory(Resource resource, BigDecimal amount, Instant effectiveFrom, CostSource source,
                        UUID recordedByMembershipId, Supplier supplier, String sku, String note) {
        this.id = UUID.randomUUID();
        this.organizationId = resource.getOrganizationId();
        this.resourceId = resource.getId();
        this.amount = amount;
        this.effectiveFrom = effectiveFrom;
        this.source = source;
        this.recordedByMembershipId = recordedByMembershipId;
        this.supplier = supplier;
        this.sku = PricingText.optional(sku, SKU_MAX, "SKU", "sku");
        this.note = PricingText.optional(note, NOTE_MAX, "Note", "note");
    }
}

package org.andre.construx.modules.pricing.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.shared.validation.DomainValidation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A price seen for a material at a supplier on a day, in MXN including IVA. Immutable: a mistake is
 * fixed by deleting it and recording a new one.
 */
@Entity
@Table(name = "price_observation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceObservation {
    public static final int SKU_MAX = 64;
    /** numeric(12,2) holds at most ten integer digits. */
    private static final BigDecimal PRICE_LIMIT = new BigDecimal("10000000000");

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private LocalDate observedOn;

    @Column(length = SKU_MAX)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PriceSource source;

    @Column(nullable = false)
    private Instant createdAt;

    /** {@code today} is the latest allowed observation day; {@code createdAt} orders same-day records. */
    public PriceObservation(Material material, Supplier supplier, BigDecimal price, LocalDate observedOn, String sku,
                            PriceSource source, LocalDate today, Instant createdAt) {
        this.material = DomainValidation.required(material, "Material is required");
        this.supplier = DomainValidation.required(supplier, "Supplier is required");
        if (!sameOrganization(material.getOrganization(), supplier.getOrganization())) {
            throw new IllegalArgumentException("Material and supplier must belong to the same organization");
        }
        if (material.isArchived()) {
            throw new PricingConflictException("ARCHIVED_MATERIAL", "Archived materials cannot receive new prices");
        }
        if (supplier.isArchived()) {
            throw new PricingConflictException("ARCHIVED_SUPPLIER", "Archived suppliers cannot receive new prices");
        }
        this.organization = material.getOrganization();
        this.price = checkPrice(price);
        this.observedOn = checkObservedOn(observedOn, today);
        this.sku = checkSku(sku);
        this.source = DomainValidation.required(source, "Source is required");
        this.createdAt = DomainValidation.required(createdAt, "Creation time is required");
    }

    /** The price rule on its own, so an import can check a row before its material exists. */
    public static BigDecimal checkPrice(BigDecimal value) {
        if (value == null) {
            throw new PricingRuleException("price", "REQUIRED", "Price is required");
        }
        if (value.signum() <= 0) {
            throw new PricingRuleException("price", "INVALID", "Price must be greater than zero");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new PricingRuleException("price", "TOO_MANY_DECIMALS", "Price must have at most two decimals");
        }
        if (value.compareTo(PRICE_LIMIT) >= 0) {
            throw new PricingRuleException("price", "INVALID", "Price is too large");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** {@code today} is the latest allowed observation day. */
    public static LocalDate checkObservedOn(LocalDate observedOn, LocalDate today) {
        if (observedOn == null) {
            throw new PricingRuleException("observedOn", "REQUIRED", "Observation date is required");
        }
        if (observedOn.isAfter(DomainValidation.required(today, "Today is required"))) {
            throw new PricingRuleException("observedOn", "FUTURE_DATE", "Observation date cannot be in the future");
        }
        return observedOn;
    }

    public static String checkSku(String sku) {
        return PricingText.optional(sku, SKU_MAX, "SKU", "sku");
    }

    private static boolean sameOrganization(Organization a, Organization b) {
        return a == b || (a != null && b != null && a.getId() != null && a.getId().equals(b.getId()));
    }
}

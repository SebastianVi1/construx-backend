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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One entry of the organization's Catalog: a Material, Labor category or Gang, Equipment rate or Work item,
 * identified by its code (ADR 0008). {@code currentCost} is the newest Cost history entry of a simple
 * Resource and derived for a composite one.
 */
@Entity
@Table(name = "resource", uniqueConstraints = @UniqueConstraint(name = "uk_resource_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Resource {
    public static final int CODE_MAX = 40;
    public static final int NAME_MAX = 160;
    public static final int BRAND_MAX = 80;
    public static final int TEXT_MAX = 10000;
    public static final int REFERENCE_MAX = 120;
    public static final int SAP_CODE_MAX = 40;
    public static final int TECHNICAL_SHEET_MAX = 255;
    /** numeric(14,2) holds at most twelve integer digits. */
    private static final BigDecimal COST_LIMIT = new BigDecimal("1000000000000");
    private static final Set<ResourceType> HAND_COSTED = EnumSet.of(ResourceType.MATERIAL, ResourceType.EQUIPMENT);

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResourceType type;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id")
    private Family family;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(length = 80)
    private String brand;

    @Column(name = "is_percentage", nullable = false)
    private boolean percentage;

    private Short percentageBase;

    @Column(name = "is_composite", nullable = false)
    private boolean composite;

    @Column(name = "is_hourly_cost", nullable = false)
    private boolean hourlyCost;

    @Column(nullable = false)
    private boolean discontinued;

    @Column(length = 120)
    private String reference;

    @Column(length = 40)
    private String sapCode;

    @Column(length = 255)
    private String technicalSheet;

    @Column(length = 40)
    private String grouper;

    @Column(columnDefinition = "text")
    private String procedure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogSource source;

    @Column(precision = 14, scale = 2)
    private BigDecimal currentCost;

    private Instant costUpdatedAt;

    @Column(precision = 14, scale = 2)
    private BigDecimal importedCost;

    private LocalDate importedCostOn;

    private Instant missingFromImportAt;

    private Instant archivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * A simple Material or Equipment rate typed in by hand, without cost until {@link #recordCost} runs.
     * Labor categories, Gangs and Work items come with their own slices.
     */
    public static Resource manual(UUID organizationId, ResourceType type, String code, String name,
                                  String description, Unit unit, Family family, Supplier supplier, String brand,
                                  Instant at) {
        if (!HAND_COSTED.contains(type)) {
            throw new PricingRuleException("type", "INVALID", "Only materials and equipment can be created by hand");
        }
        Resource r = new Resource();
        r.id = UUID.randomUUID();
        r.organizationId = organizationId;
        r.type = type;
        r.code = checkCode(code);
        r.source = CatalogSource.MANUAL;
        r.createdAt = at;
        r.describe(name, description, unit, family, supplier, brand, false, null, null, null, null, at);
        return r;
    }

    /** Trimmed and upper-cased, 1 to {@value #CODE_MAX} characters, never otherwise normalized. */
    public static String checkCode(String code) {
        String cleaned = code == null ? null : code.strip();
        if (cleaned == null || cleaned.isEmpty() || cleaned.length() > CODE_MAX) {
            throw new PricingRuleException("code", cleaned == null || cleaned.isEmpty() ? "REQUIRED" : "INVALID",
                    "Code must have 1 to " + CODE_MAX + " characters");
        }
        return cleaned.toUpperCase(Locale.ROOT);
    }

    /** A cost: zero or more, at most two decimals, below 10^12. */
    public static BigDecimal checkCost(BigDecimal value, String field) {
        if (value == null) {
            throw new PricingRuleException(field, "REQUIRED", "Cost is required");
        }
        if (value.signum() < 0) {
            throw new PricingRuleException(field, "INVALID", "Cost cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new PricingRuleException(field, "TOO_MANY_DECIMALS", "Cost must have at most two decimals");
        }
        if (value.compareTo(COST_LIMIT) >= 0) {
            throw new PricingRuleException(field, "INVALID", "Cost is too large");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Replaces the descriptive fields. A Unit, Family or Supplier that is archived can be kept but not newly
     * chosen; a brand only fits a Material.
     */
    public void describe(String name, String description, Unit unit, Family family, Supplier supplier, String brand,
                         boolean discontinued, String reference, String sapCode, String technicalSheet,
                         String procedure, Instant at) {
        String cleanName = PricingText.required(name, NAME_MAX, "Name", "name");
        String cleanBrand = PricingText.optional(brand, BRAND_MAX, "Brand", "brand");
        if (cleanBrand != null && type != ResourceType.MATERIAL) {
            throw new PricingRuleException("brand", "INVALID", "Only materials have a brand");
        }
        if (unit == null) {
            throw new PricingRuleException("unitId", "REQUIRED", "Unit is required");
        }
        if (!sameId(unit.getId(), this.unit == null ? null : this.unit.getId()) && unit.getArchivedAt() != null) {
            throw new PricingConflictException("ARCHIVED_UNIT", "Archived units cannot be chosen");
        }
        if (family != null && !sameId(family.getId(), this.family == null ? null : this.family.getId()) && family.getArchivedAt() != null) {
            throw new PricingConflictException("ARCHIVED_FAMILY", "Archived families cannot be chosen");
        }
        if (supplier != null && !sameId(supplier.getId(), this.supplier == null ? null : this.supplier.getId())
                && supplier.isArchived()) {
            throw new PricingConflictException("ARCHIVED_SUPPLIER", "Archived suppliers cannot be chosen");
        }
        this.name = cleanName;
        this.description = PricingText.optional(description, TEXT_MAX, "Description", "description");
        this.reference = PricingText.optional(reference, REFERENCE_MAX, "Reference", "reference");
        this.sapCode = PricingText.optional(sapCode, SAP_CODE_MAX, "SAP code", "sapCode");
        this.technicalSheet = PricingText.optional(technicalSheet, TECHNICAL_SHEET_MAX, "Technical sheet",
                "technicalSheet");
        this.procedure = PricingText.optional(procedure, TEXT_MAX, "Procedure", "procedure");
        this.unit = unit;
        this.family = family;
        this.supplier = supplier;
        this.brand = cleanBrand;
        this.discontinued = discontinued;
        this.updatedAt = at;
    }

    /**
     * A new Cost history entry effective now that becomes the current cost, even when the amount is unchanged
     * (it records who confirmed it). Entries of one Resource never share an instant, so the newest is current.
     */
    public ResourceCostHistory recordCost(BigDecimal amount, Instant at, UUID recordedByMembershipId,
                                          Supplier supplier, String sku, String note) {
        if (composite) {
            throw new PricingConflictException("DERIVED_COST", "The cost of a composite resource is derived");
        }
        if (type == ResourceType.LABOR) {
            throw new PricingConflictException("LABOR_COST_FROM_WAGE",
                    "A labor cost comes from its wage; change the nominal wage instead");
        }
        if (isArchived()) {
            throw new PricingConflictException("ARCHIVED_RESOURCE", "Archived resources cannot receive new costs");
        }
        if (supplier != null && supplier.isArchived()) {
            throw new PricingConflictException("ARCHIVED_SUPPLIER", "Archived suppliers cannot be chosen");
        }
        BigDecimal checked = checkCost(amount, "amount");
        Instant effectiveFrom = costUpdatedAt != null && !at.isAfter(costUpdatedAt)
                ? costUpdatedAt.plusNanos(1000) : at;
        ResourceCostHistory entry = new ResourceCostHistory(this, checked, effectiveFrom, CostSource.MANUAL,
                recordedByMembershipId, supplier, sku, note);
        this.currentCost = checked;
        this.costUpdatedAt = effectiveFrom;
        this.updatedAt = at;
        return entry;
    }

    /** Keeps the first archive time; archiving twice changes nothing. */
    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = at;
            updatedAt = at;
        }
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    private static boolean sameId(UUID a, UUID b) {
        return a != null && a.equals(b);
    }
}

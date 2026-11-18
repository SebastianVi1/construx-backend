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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of the organization's Catalog, identified by its code (ADR 0008). A simple Resource's current cost is
 * its latest Cost history entry, kept in {@link #currentCost} by {@link #recordCost}; a composite's cost is derived
 * from its components and has no history.
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

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = CODE_MAX)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ResourceType type;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id")
    private Family family;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(length = BRAND_MAX)
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
    private ResourceSource source;

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

    /** A simple Resource without a cost yet; {@code family} and {@code supplier} are optional. */
    public Resource(Organization organization, String code, ResourceType type, String name, Unit unit, Family family,
                    Supplier supplier, ResourceSource source, Instant now) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        this.code = PricingText.code(code, CODE_MAX, "Resource code", "code");
        this.type = DomainValidation.required(type, "Resource type is required");
        this.name = PricingText.required(name, NAME_MAX, "Resource name", "name");
        this.unit = sameOrganization(DomainValidation.required(unit, "Unit is required"), unit.getOrganization());
        this.family = family == null ? null : sameOrganization(family, family.getOrganization());
        this.supplier = supplier == null ? null : sameOrganization(supplier, supplier.getOrganization());
        this.source = DomainValidation.required(source, "Source is required");
        this.createdAt = DomainValidation.required(now, "Creation time is required");
        this.updatedAt = now;
    }

    /**
     * Appends a Cost history entry and makes it the current cost, so both are written in the same transaction.
     * {@code recordedBy}, {@code supplier}, {@code sku} and {@code note} are optional.
     */
    public ResourceCostHistory recordCost(BigDecimal amount, CostSource source, OrganizationMembership recordedBy,
                                          Supplier supplier, String sku, String note, Instant at) {
        if (supplier != null) {
            sameOrganization(supplier, supplier.getOrganization());
        }
        ResourceCostHistory entry = new ResourceCostHistory(this, amount, source, recordedBy, supplier, sku, note, at);
        this.currentCost = entry.getAmount();
        this.costUpdatedAt = entry.getEffectiveFrom();
        this.updatedAt = entry.getEffectiveFrom();
        return entry;
    }

    /** Keeps the first archive time; archiving twice changes nothing. */
    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = DomainValidation.required(at, "Archive time is required");
            updatedAt = at;
        }
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    private <T> T sameOrganization(T row, Organization other) {
        boolean same = organization == other || (other != null && organization.getId() != null
                && organization.getId().equals(other.getId()));
        if (!same) {
            throw new IllegalArgumentException("Catalog rows must belong to the Resource's organization");
        }
        return row;
    }
}

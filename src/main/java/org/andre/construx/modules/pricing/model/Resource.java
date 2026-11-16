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
}

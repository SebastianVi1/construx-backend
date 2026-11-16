package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A unit of measure of the organization's Catalog ({@code M2}, {@code JOR}, {@code %}). */
@Entity
@Table(name = "unit", uniqueConstraints = @UniqueConstraint(name = "uk_unit_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Unit {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(length = 120)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogSource source;

    private Instant archivedAt;
}

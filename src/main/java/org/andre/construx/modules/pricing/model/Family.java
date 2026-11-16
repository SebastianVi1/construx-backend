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

/** A grouping of Resources in the organization's Catalog (Neodata {@code Familias}). */
@Entity
@Table(name = "family", uniqueConstraints = @UniqueConstraint(name = "uk_family_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogSource source;

    private Instant archivedAt;
}

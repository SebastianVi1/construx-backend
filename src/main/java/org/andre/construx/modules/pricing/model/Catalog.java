package org.andre.construx.modules.pricing.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.shared.validation.DomainValidation;

import java.time.Instant;
import java.util.UUID;

/** The one Catalog of an organization (ADR 0008); its rows reference the organization, not the Catalog. */
@Entity
@Table(name = "catalog", uniqueConstraints = @UniqueConstraint(name = "uk_catalog_organization",
        columnNames = "organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Catalog {
    /** Only Mexican pesos in v1. */
    public static final String CURRENCY = "MXN";

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    public Catalog(Organization organization, Instant createdAt) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        this.currency = CURRENCY;
        this.createdAt = DomainValidation.required(createdAt, "Creation time is required");
    }
}

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

/** A unit of measure of the organization's Catalog ({@code M2}, {@code JOR}, {@code %}); the code never changes. */
@Entity
@Table(name = "unit", uniqueConstraints = @UniqueConstraint(name = "uk_unit_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Unit {
    public static final int CODE_MAX = 20;
    public static final int DESCRIPTION_MAX = 120;

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = CODE_MAX)
    private String code;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogEntrySource source;

    private Instant archivedAt;

    public Unit(Organization organization, String code, String description, CatalogEntrySource source) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        this.code = PricingText.code(code, CODE_MAX, "Unit code", "code");
        this.description = PricingText.optional(description, DESCRIPTION_MAX, "Unit description", "description");
        this.source = DomainValidation.required(source, "Source is required");
    }

    /** Keeps the first archive time; archiving twice changes nothing. */
    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = DomainValidation.required(at, "Archive time is required");
        }
    }
}

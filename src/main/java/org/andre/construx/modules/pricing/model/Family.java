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

/** A group of Resources of the organization's Catalog (Neodata {@code Familias}); the code never changes. */
@Entity
@Table(name = "family", uniqueConstraints = @UniqueConstraint(name = "uk_family_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family {
    public static final int CODE_MAX = 40;
    public static final int NAME_MAX = 160;

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = CODE_MAX)
    private String code;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogEntrySource source;

    private Instant archivedAt;

    public Family(Organization organization, String code, String name, CatalogEntrySource source) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        this.code = PricingText.code(code, CODE_MAX, "Family code", "code");
        this.name = PricingText.required(name, NAME_MAX, "Family name", "name");
        this.source = DomainValidation.required(source, "Source is required");
    }

    /** Keeps the first archive time; archiving twice changes nothing. */
    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = DomainValidation.required(at, "Archive time is required");
        }
    }
}

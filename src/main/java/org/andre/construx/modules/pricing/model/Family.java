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
import org.andre.construx.shared.validation.DomainValidation;

import java.time.Instant;
import java.util.UUID;

/** A grouping of Resources in the organization's Catalog (Neodata {@code Familias}). The code never changes. */
@Entity
@Table(name = "family", uniqueConstraints = @UniqueConstraint(name = "uk_family_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Family {
    public static final int CODE_MAX = 40;
    public static final int NAME_MAX = 160;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = CODE_MAX)
    private String code;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogSource source;

    private Instant archivedAt;

    /** A Family made by hand. */
    public Family(UUID organizationId, String code, String name) {
        this.id = UUID.randomUUID();
        this.organizationId = DomainValidation.required(organizationId, "Organization is required");
        this.code = PricingText.code(code, CODE_MAX, "Family code");
        this.source = CatalogSource.MANUAL;
        rename(name);
    }

    public void rename(String name) {
        this.name = PricingText.required(name, NAME_MAX, "Family name", "name");
    }

    /** Keeps the first archive time; archiving twice changes nothing. */
    public void archive(Instant at) {
        if (archivedAt == null) {
            archivedAt = DomainValidation.required(at, "Archive time is required");
        }
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Guards choosing this Family for a new or changed Resource. */
    public void requireUsable() {
        if (isArchived()) {
            throw new PricingConflictException("ARCHIVED_FAMILY", "This family is archived");
        }
    }
}

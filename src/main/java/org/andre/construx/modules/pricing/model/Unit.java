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

/** A unit of measure of the organization's Catalog ({@code M2}, {@code JOR}, {@code %}). The code never changes. */
@Entity
@Table(name = "unit", uniqueConstraints = @UniqueConstraint(name = "uk_unit_org_code",
        columnNames = {"organization_id", "code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Unit {
    public static final int CODE_MAX = 20;
    public static final int DESCRIPTION_MAX = 120;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = CODE_MAX)
    private String code;

    @Column(length = DESCRIPTION_MAX)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CatalogSource source;

    private Instant archivedAt;

    /** A Unit made by hand. */
    public Unit(UUID organizationId, String code, String description) {
        this.id = UUID.randomUUID();
        this.organizationId = DomainValidation.required(organizationId, "Organization is required");
        this.code = PricingText.code(code, CODE_MAX, "Unit code");
        this.source = CatalogSource.MANUAL;
        describe(description);
    }

    /** Blank clears the description. */
    public void describe(String description) {
        this.description = PricingText.optional(description, DESCRIPTION_MAX, "Description", "description");
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

    /** Guards choosing this Unit for a new or changed Resource. */
    public void requireUsable() {
        if (isArchived()) {
            throw new PricingConflictException("ARCHIVED_UNIT", "This unit is archived");
        }
    }
}

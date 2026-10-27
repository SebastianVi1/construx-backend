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

/** A catalog entry of one organization, unique on name + unit + brand ignoring case and extra whitespace. */
@Entity
@Table(name = "material", uniqueConstraints = @UniqueConstraint(name = "uk_material_org_key",
        columnNames = {"organization_id", "normalized_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Material {
    public static final int NAME_MAX = 160;
    public static final int BRAND_MAX = 80;

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaterialUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MaterialCategory category;

    @Column(length = BRAND_MAX)
    private String brand;

    @Column(nullable = false, length = 300)
    private String normalizedKey;

    private Instant archivedAt;

    public Material(Organization organization, String name, MaterialUnit unit, MaterialCategory category,
                    String brand) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        describe(name, unit, category, brand);
    }

    public void describe(String name, MaterialUnit unit, MaterialCategory category, String brand) {
        String cleanName = PricingText.required(name, NAME_MAX, "Material name", "name");
        String cleanBrand = PricingText.optional(brand, BRAND_MAX, "Brand", "brand");
        this.unit = DomainValidation.required(unit, "Unit is required");
        this.category = DomainValidation.required(category, "Category is required");
        this.name = cleanName;
        this.brand = cleanBrand;
        this.normalizedKey = keyOf(cleanName, unit, cleanBrand);
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

    public static String keyOf(String name, MaterialUnit unit, String brand) {
        return PricingText.fold(name) + PricingText.SEPARATOR + unit + PricingText.SEPARATOR + PricingText.fold(brand);
    }
}

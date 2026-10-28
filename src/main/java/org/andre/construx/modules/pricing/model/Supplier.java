package org.andre.construx.modules.pricing.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.shared.validation.DomainValidation;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.UUID;

/** One store or branch an organization buys from, unique on name + branch ignoring case and extra whitespace. */
@Entity
@Table(name = "supplier", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_org_key",
        columnNames = {"organization_id", "normalized_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier {
    public static final int NAME_MAX = 160;
    public static final int BRANCH_MAX = 120;
    public static final int WEBSITE_MAX = 255;

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(length = BRANCH_MAX)
    private String branch;

    @Column(length = WEBSITE_MAX)
    private String website;

    @Column(nullable = false, length = 300)
    private String normalizedKey;

    private Instant archivedAt;

    public Supplier(Organization organization, String name, String branch, String website) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        describe(name, branch, website);
    }

    public void describe(String name, String branch, String website) {
        String cleanName = PricingText.required(name, NAME_MAX, "Supplier name", "name");
        String cleanBranch = PricingText.optional(branch, BRANCH_MAX, "Branch", "branch");
        String cleanWebsite = website(website);
        this.name = cleanName;
        this.branch = cleanBranch;
        this.website = cleanWebsite;
        this.normalizedKey = keyOf(cleanName, cleanBranch);
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

    public static String keyOf(String name, String branch) {
        return PricingText.fold(name) + PricingText.SEPARATOR + PricingText.fold(branch);
    }

    private static String website(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.length() > WEBSITE_MAX) {
            throw new IllegalArgumentException("Website must have at most " + WEBSITE_MAX + " characters");
        }
        URI uri;
        try {
            uri = new URI(cleaned);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Website must be an http or https URL");
        }
        String scheme = uri.getScheme();
        boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!web || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Website must be an http or https URL");
        }
        return cleaned;
    }
}

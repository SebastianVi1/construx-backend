package org.andre.construx.modules.pricing.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.hibernate.annotations.ColumnDefault;
import org.andre.construx.shared.validation.DomainValidation;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One store or branch an organization buys from, unique on name + branch ignoring case and extra whitespace,
 * and on its code when it has one. The code of an imported supplier is the import's key and never changes here.
 */
@Entity
@Table(name = "supplier", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_org_key",
        columnNames = {"organization_id", "normalized_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier {
    public static final int NAME_MAX = 160;
    public static final int BRANCH_MAX = 120;
    public static final int WEBSITE_MAX = 255;
    public static final int CODE_MAX = 40;
    public static final int RFC_MAX = 13;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

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

    @Column(length = CODE_MAX)
    private String code;

    @Column(length = RFC_MAX)
    private String rfc;

    @Column(length = 255)
    private String address;

    @Column(length = 120)
    private String neighborhood;

    @Column(length = 160)
    private String city;

    @Column(length = 80)
    private String state;

    @Column(length = 10)
    private String postalCode;

    @Column(length = 40)
    private String phone;

    @Column(length = 40)
    private String mobile;

    @Column(length = 40)
    private String fax;

    @Column(length = 160)
    private String email;

    @Column(length = 160)
    private String contact;

    @Column(length = 500)
    private String notes;

    @ColumnDefault("false")
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MANUAL'")
    @Column(nullable = false, length = 20)
    private CatalogSource source = CatalogSource.MANUAL;

    private Instant archivedAt;

    public Supplier(Organization organization, String name, String branch, String website) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        describe(name, branch, website);
    }

    /** Contact and tax fields of a supplier (docs/pricing.md §1.4); blank text clears a field. */
    public record Details(String rfc, String address, String neighborhood, String city, String state,
                         String postalCode, String phone, String mobile, String fax, String email, String contact,
                         String notes, boolean isDefault) {}

    public Details details() {
        return new Details(rfc, address, neighborhood, city, state, postalCode, phone, mobile, fax, email, contact,
                notes, isDefault);
    }

    public void detail(Details details) {
        String cleanRfc = PricingText.optionalCode(details.rfc(), RFC_MAX, "RFC", "rfc");
        String cleanAddress = PricingText.optional(details.address(), 255, "Address", "address");
        String cleanNeighborhood = PricingText.optional(details.neighborhood(), 120, "Neighborhood", "neighborhood");
        String cleanCity = PricingText.optional(details.city(), 160, "City", "city");
        String cleanState = PricingText.optional(details.state(), 80, "State", "state");
        String cleanPostalCode = PricingText.optional(details.postalCode(), 10, "Postal code", "postalCode");
        String cleanPhone = PricingText.optional(details.phone(), 40, "Phone", "phone");
        String cleanMobile = PricingText.optional(details.mobile(), 40, "Mobile", "mobile");
        String cleanFax = PricingText.optional(details.fax(), 40, "Fax", "fax");
        String cleanEmail = email(details.email());
        String cleanContact = PricingText.optional(details.contact(), 160, "Contact", "contact");
        String cleanNotes = PricingText.optional(details.notes(), 500, "Notes", "notes");
        this.rfc = cleanRfc;
        this.address = cleanAddress;
        this.neighborhood = cleanNeighborhood;
        this.city = cleanCity;
        this.state = cleanState;
        this.postalCode = cleanPostalCode;
        this.phone = cleanPhone;
        this.mobile = cleanMobile;
        this.fax = cleanFax;
        this.email = cleanEmail;
        this.contact = cleanContact;
        this.notes = cleanNotes;
        this.isDefault = details.isDefault();
    }

    /** Trimmed and upper-cased; blank clears it. An imported supplier keeps its code ({@code IMPORTED_FIELD}). */
    public void recode(String code) {
        String cleaned = codeOf(code);
        if (isImported() && !Objects.equals(cleaned, this.code)) {
            throw new PricingConflictException("IMPORTED_FIELD", "The code of an imported supplier cannot change");
        }
        this.code = cleaned;
    }

    public boolean isImported() {
        return source == CatalogSource.NEODATA_CONSTRUBASE;
    }

    /** The code as it would be stored, or null. */
    public static String codeOf(String code) {
        return PricingText.optionalCode(code, CODE_MAX, "Supplier code", "code");
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

    private static String email(String value) {
        String cleaned = PricingText.optional(value, 160, "Email", "email");
        if (cleaned != null && !EMAIL.matcher(cleaned).matches()) {
            throw new PricingRuleException("email", "INVALID", "Email must be a valid address");
        }
        return cleaned;
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

package org.andre.construx.modules.project.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotBlank @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @Size(max = 255)
    @Column(length = 255)
    private String address;

    private LocalDate startDate;

    private LocalDate expectedEndDate;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.PLANNED;

    /** Optional allocated budget in the organization's currency; null means not set. */
    @Column(precision = 14, scale = 2)
    private BigDecimal allocatedBudget;

    @OneToMany(mappedBy = "project")
    private Set<ProjectAccess> accesses = new HashSet<>();

    @OneToMany(mappedBy = "project")
    private Set<Goal> goals = new HashSet<>();

    public Project(Organization organization, String name) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        rename(name);
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Project name must have 1 to 160 characters");
        }
        this.name = name.strip();
    }

    /** Optional site details; a blank address is stored as null. */
    public void describe(String address, LocalDate startDate, LocalDate expectedEndDate) {
        String normalized = address == null || address.isBlank() ? null : address.strip();
        if (normalized != null && normalized.length() > 255) {
            throw new IllegalArgumentException("Address must have at most 255 characters");
        }
        if (startDate != null && expectedEndDate != null && expectedEndDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Expected end date cannot be before the start date");
        }
        this.address = normalized;
        this.startDate = startDate;
        this.expectedEndDate = expectedEndDate;
    }

    public void changeStatus(ProjectStatus status) {
        this.status = DomainValidation.required(status, "Project status is required");
    }

    /** Sets or, with null, clears the budget; it must fit numeric(14,2) and cannot be negative. */
    public void assignBudget(BigDecimal amount) {
        if (amount == null) {
            this.allocatedBudget = null;
            return;
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Allocated budget cannot be negative");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > 2) {
            throw new IllegalArgumentException("Allocated budget allows at most 2 decimals");
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (scaled.precision() > 14) {
            throw new IllegalArgumentException("Allocated budget is too large");
        }
        this.allocatedBudget = scaled;
    }

    public boolean sameAs(Project other) {
        return this == other || (other != null && id != null && id.equals(other.getId()));
    }
}

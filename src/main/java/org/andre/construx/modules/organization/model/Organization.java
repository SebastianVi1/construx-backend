package org.andre.construx.modules.organization.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.identity.model.UserAccount;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private UserAccount createdBy;

    @OneToMany(mappedBy = "organization", cascade = CascadeType.PERSIST)
    private Set<OrganizationMembership> memberships = new HashSet<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.PERSIST)
    private Set<OrganizationRole> roles = new HashSet<>();

    public Organization(String name, UserAccount createdBy) {
        rename(name);
        this.createdBy = java.util.Objects.requireNonNull(createdBy, "Creator is required");
        OrganizationRole ownerRole = OrganizationRole.owner(this);
        roles.add(ownerRole);
        memberships.add(new OrganizationMembership(this, createdBy, ownerRole));
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("Organization name must have 1 to 160 characters");
        }
        this.name = name.strip();
    }

    public boolean sameAs(Organization other) {
        return this == other || (other != null && id != null && id.equals(other.getId()));
    }
}

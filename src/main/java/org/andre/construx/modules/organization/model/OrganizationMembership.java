package org.andre.construx.modules.organization.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.project.model.ProjectAccess;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organization_membership", uniqueConstraints =
        @UniqueConstraint(name = "uk_membership_org_user", columnNames = {"organization_id", "user_account_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationMembership {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount userAccount;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private OrganizationRole role;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @OneToMany(mappedBy = "organizationMembership")
    private Set<ProjectAccess> projectAccesses = new HashSet<>();

    public OrganizationMembership(Organization organization, UserAccount userAccount, OrganizationRole role) {
        this.organization = Objects.requireNonNull(organization, "Organization is required");
        this.userAccount = Objects.requireNonNull(userAccount, "User is required");
        changeRole(role);
        if (role.isSystemRole() && !userAccount.sameAs(organization.getCreatedBy())) {
            throw new IllegalArgumentException("Only the organization creator can have the owner role");
        }
    }

    public void changeRole(OrganizationRole role) {
        Objects.requireNonNull(role, "Role is required");
        if (!organization.sameAs(role.getOrganization())) {
            throw new IllegalArgumentException("Role belongs to another organization");
        }
        if (this.role != null && this.role.isSystemRole()) {
            throw new IllegalStateException("Owner role cannot be replaced");
        }
        this.role = role;
    }

    public void suspend() {
        if (role.isSystemRole()) {
            throw new IllegalStateException("Owner membership cannot be suspended");
        }
        this.status = MembershipStatus.SUSPENDED;
    }

    public void leave() {
        if (role.isSystemRole()) {
            throw new IllegalStateException("Owner membership cannot leave");
        }
        this.status = MembershipStatus.LEFT;
    }

    public void activate() {
        this.status = MembershipStatus.ACTIVE;
    }
}

package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.identity.model.UserAccount;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_invitation", indexes =
        @Index(name = "ix_invitation_org_email", columnList = "organization_id,email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationInvitation {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Email @NotBlank @Size(max = 254)
    @Column(nullable = false, length = 254)
    private String email;

    @NotBlank
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private OrganizationRole role;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_membership_id", nullable = false)
    private OrganizationMembership invitedBy;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status = InvitationStatus.PENDING;

    @NotNull
    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;

    public OrganizationInvitation(Organization organization, String email, String tokenHash,
                                  OrganizationRole role, OrganizationMembership invitedBy, Instant expiresAt) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        this.email = UserAccount.normalizeEmail(email);
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("Token hash is required");
        }
        this.tokenHash = tokenHash;
        this.role = DomainValidation.required(role, "Role is required");
        this.invitedBy = DomainValidation.required(invitedBy, "Inviter is required");
        if (!organization.sameAs(role.getOrganization())
                || !organization.sameAs(invitedBy.getOrganization())) {
            throw new IllegalArgumentException("Invitation relations must belong to the same organization");
        }
        if (invitedBy.getStatus() != MembershipStatus.ACTIVE) {
            throw new IllegalArgumentException("Inviter must be an active member");
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Invitation must expire in the future");
        }
        this.expiresAt = expiresAt;
    }

    public OrganizationMembership accept(UserAccount user, Instant now) {
        DomainValidation.required(user, "User is required");
        DomainValidation.required(now, "Acceptance time is required");
        if (status != InvitationStatus.PENDING || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("Invitation is not active");
        }
        if (!email.equals(user.getEmail())) {
            throw new IllegalArgumentException("Invitation email does not match user");
        }
        status = InvitationStatus.ACCEPTED;
        acceptedAt = now;
        return new OrganizationMembership(organization, user, role);
    }

    public void revoke() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only pending invitations can be revoked");
        }
        status = InvitationStatus.REVOKED;
    }
}

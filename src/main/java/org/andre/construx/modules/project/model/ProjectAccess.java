package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "project_access", uniqueConstraints =
        @UniqueConstraint(name = "uk_project_access_member", columnNames = {"project_id", "organization_membership_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectAccess {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_membership_id", nullable = false)
    private OrganizationMembership organizationMembership;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    public ProjectAccess(Project project, OrganizationMembership membership) {
        this.project = Objects.requireNonNull(project, "Project is required");
        this.organizationMembership = Objects.requireNonNull(membership, "Membership is required");
        if (!project.getOrganization().sameAs(membership.getOrganization())) {
            throw new IllegalArgumentException("Membership and project belong to different organizations");
        }
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new IllegalArgumentException("Project access requires an active membership");
        }
    }
}

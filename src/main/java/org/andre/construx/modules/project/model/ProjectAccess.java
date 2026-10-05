package org.andre.construx.modules.project.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.util.UUID;

@Entity
@Table(name = "project_access")
@AllArgsConstructor @NoArgsConstructor
@Getter
public class ProjectAccess {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_membership_id", nullable = false)
    private OrganizationMembership organizationMembership;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

}

package org.andre.construx.modules.organization.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.project.model.ProjectAccess;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@AllArgsConstructor @NoArgsConstructor
@Getter
@Table(name = "organization_membership")
public class OrganizationMembership {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount userAccount;

    @OneToMany(mappedBy = "organizationMembership")
    private Set<ProjectAccess> projectAccesses = new HashSet<>();

}

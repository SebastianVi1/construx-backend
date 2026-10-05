package org.andre.construx.modules.organization.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.identity.model.UserAccount;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@NoArgsConstructor @AllArgsConstructor
@Builder @Table(name = "organization")
@Getter
public class Organization {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount createdBy;

    @OneToMany(mappedBy = "organization")
    @Builder.Default
    private Set<OrganizationMembership> organizationMemberships = new HashSet<>();

}

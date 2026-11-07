package org.andre.construx.modules.crew.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "crew")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Crew {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 120)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leader_membership_id", nullable = false)
    private OrganizationMembership leader;

    @OneToMany(mappedBy = "crew", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<CrewMember> members = new HashSet<>();

    @Column(nullable = false)
    private Instant createdAt;

    public Crew(Organization organization, String name, OrganizationMembership leader) {
        this.id = UUID.randomUUID();
        this.organization = organization;
        this.name = name.trim();
        this.leader = leader;
        this.createdAt = Instant.now();
        // leader is automatically a member
        this.members.add(new CrewMember(this, leader));
    }

    public void setName(String name) {
        this.name = name.trim();
    }

    public void setLeader(OrganizationMembership leader) {
        this.leader = leader;
    }

    public void addMember(OrganizationMembership membership) {
        this.members.add(new CrewMember(this, membership));
    }

    public void removeMember(OrganizationMembership membership) {
        this.members.removeIf(m -> m.getMembershipId().equals(membership.getId()));
    }
}

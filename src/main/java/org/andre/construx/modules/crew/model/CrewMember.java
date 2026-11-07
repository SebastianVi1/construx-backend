package org.andre.construx.modules.crew.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.util.UUID;

@Entity
@Table(name = "crew_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrewMember {

    @EmbeddedId
    private CrewMemberId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("crewId")
    @JoinColumn(name = "crew_id")
    private Crew crew;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("membershipId")
    @JoinColumn(name = "membership_id")
    private OrganizationMembership membership;

    public CrewMember(Crew crew, OrganizationMembership membership) {
        this.id = new CrewMemberId(crew.getId(), membership.getId());
        this.crew = crew;
        this.membership = membership;
    }

    public UUID getMembershipId() {
        return id.getMembershipId();
    }
}

package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CrewService {

    private final CrewRepository crews;
    private final MembershipDirectory memberships;

    public CrewService(CrewRepository crews,
                       MembershipDirectory memberships) {
        this.crews = crews;
        this.memberships = memberships;
    }

    public record CrewData(UUID id, UUID organizationId, String name, UUID leaderMembershipId,
                           List<UUID> memberMembershipIds, Instant createdAt) {}

    @Transactional
    public CrewData createCrew(OrgScope org, String name, UUID leaderMembershipId) {
        org.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership leader = loadActiveMembership(leaderMembershipId, org.organizationId());
        Crew crew = crews.save(new Crew(org.membership().getOrganization(), name, leader));
        return toData(crew);
    }

    @Transactional
    public CrewData addMember(OrgScope org, UUID crewId, UUID membershipId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, org.organizationId());
        OrganizationMembership membership = loadActiveMembership(membershipId, org.organizationId());
        crew.addMember(membership);
        return toData(crew);
    }

    @Transactional
    public void removeMember(OrgScope org, UUID crewId, UUID membershipId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, org.organizationId());
        if (crew.getLeader().getId().equals(membershipId)) {
            throw new RuleViolationException("CREW_LEADER_CANNOT_BE_REMOVED",
                    "Cannot remove the crew leader; change the leader first");
        }
        OrganizationMembership membership = loadActiveMembership(membershipId, org.organizationId());
        crew.removeMember(membership);
    }

    @Transactional(readOnly = true)
    public List<CrewData> listCrews(OrgScope org) {
        org.require(PermissionAction.VIEW_PLAN);
        return crews.findByOrganizationId(org.organizationId()).stream().map(this::toData).toList();
    }

    @Transactional
    public void deleteCrew(OrgScope org, UUID crewId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, org.organizationId());
        crews.delete(crew);
    }

    private Crew loadCrew(UUID crewId, UUID organizationId) {
        return crews.findByIdAndOrganizationId(crewId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Crew not found"));
    }

    private OrganizationMembership loadActiveMembership(UUID membershipId, UUID organizationId) {
        return memberships.find(organizationId, membershipId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    }

    private CrewData toData(Crew crew) {
        List<UUID> memberIds = crew.getMembers().stream()
                .map(m -> m.getId().getMembershipId())
                .toList();
        return new CrewData(crew.getId(), crew.getOrganization().getId(), crew.getName(),
                crew.getLeader().getId(), memberIds, crew.getCreatedAt());
    }
}

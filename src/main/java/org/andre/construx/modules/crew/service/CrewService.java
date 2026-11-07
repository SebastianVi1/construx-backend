package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CrewService {

    private final CrewRepository crews;
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public CrewService(CrewRepository crews, OrganizationRepository organizations,
                       OrganizationMembershipRepository memberships, MembershipGuard guard,
                       DomainAuthorization authorization) {
        this.crews = crews;
        this.organizations = organizations;
        this.memberships = memberships;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record CrewData(UUID id, UUID organizationId, String name, UUID leaderMembershipId,
                           List<UUID> memberMembershipIds, Instant createdAt) {}

    @Transactional
    public CrewData createCrew(UUID userId, UUID organizationId, String name, UUID leaderMembershipId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        OrganizationMembership leader = loadActiveMembership(leaderMembershipId, organizationId);
        Crew crew = crews.save(new Crew(org, name, leader));
        return toData(crew);
    }

    @Transactional
    public CrewData addMember(UUID userId, UUID organizationId, UUID crewId, UUID membershipId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, organizationId);
        OrganizationMembership membership = loadActiveMembership(membershipId, organizationId);
        crew.addMember(membership);
        return toData(crew);
    }

    @Transactional
    public void removeMember(UUID userId, UUID organizationId, UUID crewId, UUID membershipId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, organizationId);
        if (crew.getLeader().getId().equals(membershipId)) {
            throw new RuleViolationException("CREW_LEADER_CANNOT_BE_REMOVED",
                    "Cannot remove the crew leader; change the leader first");
        }
        OrganizationMembership membership = loadActiveMembership(membershipId, organizationId);
        crew.removeMember(membership);
    }

    @Transactional(readOnly = true)
    public List<CrewData> listCrews(UUID userId, UUID organizationId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_PLAN);
        return crews.findByOrganizationId(organizationId).stream().map(this::toData).toList();
    }

    @Transactional
    public void deleteCrew(UUID userId, UUID organizationId, UUID crewId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Crew crew = loadCrew(crewId, organizationId);
        crews.delete(crew);
    }

    private Crew loadCrew(UUID crewId, UUID organizationId) {
        return crews.findByIdAndOrganizationId(crewId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Crew not found"));
    }

    private OrganizationMembership loadActiveMembership(UUID membershipId, UUID organizationId) {
        return memberships.findByIdAndOrganizationId(membershipId, organizationId)
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

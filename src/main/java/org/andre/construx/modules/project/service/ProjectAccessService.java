package org.andre.construx.modules.project.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.LaborRateAssignment;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Who works on a site. Tasks can only be assigned to people with access to the site. */
@Service
public class ProjectAccessService {
    private final ProjectAccessRepository accesses;
    private final ActivityRepository activities;
    private final MembershipDirectory memberships;
    private final PersonDirectory directory;
    private final LaborRateService laborRates;

    public ProjectAccessService(ProjectAccessRepository accesses, ActivityRepository activities,
                                MembershipDirectory memberships, PersonDirectory directory,
                                LaborRateService laborRates) {
        this.accesses = accesses;
        this.activities = activities;
        this.memberships = memberships;
        this.directory = directory;
        this.laborRates = laborRates;
    }

    /** No email: workers can read this list. */
    public record AccessData(UUID accessId, UUID membershipId, UUID userId, String fullName,
                             String roleCode, String roleName, URI avatarUrl) {}

    /** A person on the site with their current Labor rate; the rate is null unless the caller may read rates. */
    public record SiteMember(AccessData access, LaborRateService.LaborRate laborRate) {}

    @Transactional(readOnly = true)
    public List<SiteMember> list(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        return withRates(site, staff(site.siteId()));
    }

    /** Active members with access to the site, with name and photo. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public List<AccessData> staff(UUID projectId) {
        List<ProjectAccess> list = accesses.findActiveByProject(projectId);
        Map<UUID, Person> people = directory.byUserId(list.stream()
                .map(a -> a.getOrganizationMembership().getUserAccount().getId()).toList());
        return list.stream().map(a -> toData(a, people)).toList();
    }

    @Transactional
    public SiteMember add(SiteScope site, UUID membershipId) {
        site.require(PermissionAction.MANAGE_PROJECT);
        OrganizationMembership membership = memberships.find(site.organizationId(), membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (accesses.existsByProjectIdAndOrganizationMembershipId(site.siteId(), membershipId)) {
            throw new DataIntegrityViolationException("Member already has access to this site");
        }
        ProjectAccess access = accesses.save(new ProjectAccess(site.site(), membership));
        return withRate(site, access);
    }

    /** Sets a person's Labor rate on the site (docs/pricing.md §6.1); 200 with the access and its rate. */
    @Transactional
    public SiteMember setLaborRate(SiteScope site, UUID accessId, LaborRateAssignment.Choice choice) {
        laborRates.set(site, accessId, choice);
        return withRate(site, load(site.siteId(), accessId));
    }

    private SiteMember withRate(SiteScope site, ProjectAccess access) {
        AccessData data = toData(access,
                directory.byUserId(List.of(access.getOrganizationMembership().getUserAccount().getId())));
        return withRates(site, List.of(data)).getFirst();
    }

    private List<SiteMember> withRates(SiteScope site, List<AccessData> staff) {
        Map<UUID, LaborRateService.LaborRate> rates = laborRates.visibleCurrent(site,
                staff.stream().map(AccessData::membershipId).toList());
        return staff.stream().map(a -> new SiteMember(a, rates.get(a.membershipId()))).toList();
    }

    @Transactional
    public void remove(SiteScope site, UUID accessId) {
        site.require(PermissionAction.MANAGE_PROJECT);
        ProjectAccess access = accesses.findByIdAndProjectId(accessId, site.siteId())
                .orElseThrow(() -> new ResourceNotFoundException("Site access not found"));
        if (activities.existsInAnyTeam(accessId)) {
            throw new IllegalStateException("Take this person off their task teams before removing them from the site");
        }
        accesses.delete(access);
    }

    /** Gives a member access to a site, e.g. on accepting a site invitation. No permission check: callers authorize. */
    @Transactional
    public void grant(Project site, OrganizationMembership membership) {
        accesses.save(new ProjectAccess(site, membership));
    }

    /** Ids of the sites a membership is assigned to. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public Set<UUID> siteIdsOf(UUID membershipId) {
        return accesses.findProjectIdsByMembership(membershipId);
    }

    /** Resolves a site access inside the tenant and site, for task assignment. */
    public ProjectAccess load(UUID projectId, UUID accessId) {
        return accesses.findByIdAndProjectId(accessId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Site access not found"));
    }

    private static AccessData toData(ProjectAccess access, Map<UUID, Person> people) {
        OrganizationMembership membership = access.getOrganizationMembership();
        UUID memberUserId = membership.getUserAccount().getId();
        Person person = PersonDirectory.of(people, memberUserId);
        return new AccessData(access.getId(), membership.getId(), memberUserId, person.fullName(),
                membership.getRole().getCode(), membership.getRole().getName(), person.avatarUrl());
    }
}

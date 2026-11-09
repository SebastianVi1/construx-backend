package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.crew.service.CrewDirectory;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** What a planner can drag onto the zones of a Site's floor plans: its staff, the company's crews and equipment. */
@Service
public class FloorPlanRosterService {
    private final ProjectAccessService siteStaff;
    private final CrewDirectory crews;

    public FloorPlanRosterService(ProjectAccessService siteStaff, CrewDirectory crews) {
        this.siteStaff = siteStaff;
        this.crews = crews;
    }

    /** People are the Site's staff (with Site access), so no company members list is needed. */
    public record Roster(List<RosterPerson> people, List<RosterCrew> crews, List<RosterEquipment> equipment) {}

    public record RosterPerson(UUID membershipId, String fullName, URI avatarUrl, String roleName) {}

    public record RosterCrew(UUID id, String name, int memberCount) {}

    public record RosterEquipment(UUID id, String type, String identifier) {}

    @Transactional(readOnly = true)
    public Roster roster(SiteScope site) {
        site.require(PermissionAction.MANAGE_PLAN);
        UUID organizationId = site.organizationId();
        return new Roster(
                siteStaff.staff(site.siteId()).stream()
                        .map(a -> new RosterPerson(a.membershipId(), a.fullName(), a.avatarUrl(), a.roleName()))
                        .toList(),
                crews.crews(organizationId).stream()
                        .map(c -> new RosterCrew(c.id(), c.name(), c.memberCount()))
                        .toList(),
                crews.activeEquipment(organizationId).stream()
                        .map(e -> new RosterEquipment(e.id(), e.type(), e.identifier()))
                        .toList());
    }
}

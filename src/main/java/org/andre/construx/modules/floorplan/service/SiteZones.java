package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.crew.model.CrewMember;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.floorplan.repository.ZoneAssignmentRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The zones of a Site across all its floor plans and the people its plan expects, for other modules (Attendance). It checks no permission:
 * the caller has already resolved the Site and decided who may read.
 */
@Service
public class SiteZones {
    private final FloorPlanZoneRepository zones;
    private final ZoneAssignmentRepository assignments;

    public SiteZones(FloorPlanZoneRepository zones, ZoneAssignmentRepository assignments) {
        this.zones = zones;
        this.assignments = assignments;
    }

    /** A zone with the floor plan it is drawn on. */
    public record ZoneRef(UUID id, String name, UUID floorPlanId, String floorPlanName) {}

    /** The live zones of the Site, by floor plan name and then in the order they were drawn. */
    @Transactional(readOnly = true)
    public List<ZoneRef> ofSite(UUID siteId) {
        return zones.findLiveOfSite(siteId).stream().map(SiteZones::ref).toList();
    }

    /** A live zone of the Site; unknown, deleted or of another Site is a 404. */
    @Transactional(readOnly = true)
    public FloorPlanZone live(UUID siteId, UUID zoneId) {
        return zones.findLiveOfSite(zoneId, siteId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));
    }

    /** A person the zone plan expects on the Site, and the zone it expects them in. */
    public record Expected(UUID membershipId, ZoneRef zone) {}

    /**
     * The people the plan expects on the Site on a day, once each: everyone with an open zone assignment that
     * covers the day in a live zone of the Site, directly or as a member of an assigned Crew (its members as
     * they are now). Open means not closed, started on or before the day and not ended before it. A person with
     * several such assignments is expected in the zone of the one that started earliest. Equipment is never
     * expected.
     */
    @Transactional(readOnly = true)
    public List<Expected> expected(UUID siteId, LocalDate day) {
        Map<UUID, Expected> byPerson = new LinkedHashMap<>();
        for (ZoneAssignment assignment : assignments.findOpenOfSiteCovering(siteId, day)) {
            ZoneRef zone = ref(assignment.getZone());
            if (assignment.getMembership() != null) {
                UUID person = assignment.getMembership().getId();
                byPerson.putIfAbsent(person, new Expected(person, zone));
            } else if (assignment.getCrew() != null) {
                for (CrewMember member : assignment.getCrew().getMembers()) {
                    byPerson.putIfAbsent(member.getMembershipId(), new Expected(member.getMembershipId(), zone));
                }
            }
        }
        return List.copyOf(byPerson.values());
    }

    /** Names a zone as it is now, deleted or not: a record that points at it keeps showing its name. */
    public static ZoneRef ref(FloorPlanZone zone) {
        return new ZoneRef(zone.getId(), zone.getName(), zone.getFloorPlan().getId(), zone.getFloorPlan().getName());
    }
}

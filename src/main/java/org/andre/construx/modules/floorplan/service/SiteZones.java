package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The zones of a Site across all its floor plans, for other modules (Attendance). It checks no permission:
 * the caller has already resolved the Site and decided who may read.
 */
@Service
public class SiteZones {
    private final FloorPlanZoneRepository zones;

    public SiteZones(FloorPlanZoneRepository zones) {
        this.zones = zones;
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

    /** Names a zone as it is now, deleted or not: a record that points at it keeps showing its name. */
    public static ZoneRef ref(FloorPlanZone zone) {
        return new ZoneRef(zone.getId(), zone.getName(), zone.getFloorPlan().getId(), zone.getFloorPlan().getName());
    }
}

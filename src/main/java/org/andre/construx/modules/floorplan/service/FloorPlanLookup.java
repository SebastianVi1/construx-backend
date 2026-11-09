package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.SiteScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves floor plans and zones inside a {@link SiteScope}; anything outside the Site is a 404. */
@Component
public class FloorPlanLookup {
    private final FloorPlanRepository floorPlans;
    private final FloorPlanZoneRepository zones;

    public FloorPlanLookup(FloorPlanRepository floorPlans, FloorPlanZoneRepository zones) {
        this.floorPlans = floorPlans;
        this.zones = zones;
    }

    public FloorPlan floorPlan(SiteScope site, UUID floorPlanId) {
        return floorPlans.findByIdAndProjectId(floorPlanId, site.siteId())
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
    }

    /** A live (not deleted) zone of the floor plan. */
    public FloorPlanZone zone(FloorPlan plan, UUID zoneId) {
        return zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, plan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));
    }
}

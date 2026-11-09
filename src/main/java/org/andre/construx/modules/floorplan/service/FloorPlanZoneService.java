package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.floorplan.repository.ZoneAssignmentRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FloorPlanZoneService {

    private final FloorPlanZoneRepository zones;
    private final FloorPlanCalibrationRepository calibrations;
    private final ZoneAssignmentRepository zoneAssignments;
    private final FloorPlanLookup lookup;
    private final AssignmentViews assignmentViews;
    private final AuditService auditService;

    public FloorPlanZoneService(FloorPlanZoneRepository zones,
                                FloorPlanCalibrationRepository calibrations,
                                ZoneAssignmentRepository zoneAssignments,
                                FloorPlanLookup lookup,
                                AssignmentViews assignmentViews,
                                AuditService auditService) {
        this.zones = zones;
        this.calibrations = calibrations;
        this.zoneAssignments = zoneAssignments;
        this.lookup = lookup;
        this.assignmentViews = assignmentViews;
        this.auditService = auditService;
    }

    public record ZoneData(UUID id, UUID floorPlanId, String name, String description,
                           ZoneStatus status, List<double[]> points,
                           double areaMSquared, double perimeterMeters,
                           long version, Instant createdAt, Instant updatedAt) {}

    public record CreateZoneRequest(String name, String description, ZoneStatus status, List<double[]> points) {}

    public record UpdateZoneRequest(String name, String description, ZoneStatus status,
                                    List<double[]> points, long version) {}

    @Transactional
    public ZoneData createZone(SiteScope site, UUID floorPlanId, CreateZoneRequest request) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership member = site.membership();
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);

        if (!plan.isReady()) {
            throw new RuleViolationException("FLOOR_PLAN_NOT_READY", "Floor plan must be READY to draw zones");
        }

        boolean hasCalibration = calibrations.findByFloorPlanId(floorPlanId).isPresent();
        if (!hasCalibration) {
            throw new RuleViolationException("ZONE_NO_CALIBRATION",
                    "Scale calibration is required before drawing zones");
        }

        FloorPlanZone zone = new FloorPlanZone(plan, member,
                request.name(), request.description(),
                request.status() != null ? request.status() : ZoneStatus.NOT_STARTED,
                request.points());
        zones.save(zone);
        auditService.record("FloorPlanZone", zone.getId(), "CREATE",
                member.getId(), null, zoneSnapshot(zone));
        return toData(zone);
    }

    @Transactional
    public ZoneData updateZone(SiteScope site, UUID floorPlanId, UUID zoneId, UpdateZoneRequest request) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership actor = site.membership();
        FloorPlanZone zone = lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        Map<String, Object> before = zoneSnapshot(zone);
        try {
            zone.update(request.name(), request.description(),
                    request.status() != null ? request.status() : ZoneStatus.NOT_STARTED,
                    request.points());
            zones.saveAndFlush(zone);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RuleViolationException("ZONE_CONFLICT",
                    "Zone was modified by another user; refresh and retry");
        }
        auditService.record("FloorPlanZone", zone.getId(), "UPDATE",
                actor.getId(), before, zoneSnapshot(zone));
        return toData(zone);
    }

    @Transactional
    public void deleteZone(SiteScope site, UUID floorPlanId, UUID zoneId) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership actor = site.membership();
        FloorPlanZone zone = lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        Map<String, Object> before = zoneSnapshot(zone);
        zone.softDelete();
        zones.save(zone);
        auditService.record("FloorPlanZone", zone.getId(), "DELETE",
                actor.getId(), before, null);
    }

    @Transactional(readOnly = true)
    public List<ZoneData> listZones(SiteScope site, UUID floorPlanId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.floorPlan(site, floorPlanId);

        return zones.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(floorPlanId)
                .stream()
                .map(this::toData)
                .toList();
    }

    public record ZoneStateData(ZoneData zone, List<ZoneAssignmentService.AssignmentData> activeAssignments) {}

    @Transactional(readOnly = true)
    public List<ZoneStateData> getFloorPlanState(SiteScope site, UUID floorPlanId, LocalDate date) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.floorPlan(site, floorPlanId);

        List<FloorPlanZone> allZones = zones.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(floorPlanId);

        List<ZoneAssignment> candidates = zoneAssignments
                .findByFloorPlanIdAndStartDateLessThanEqualAndClosedAtIsNull(floorPlanId, date);

        // One batch for every occupant on the plan, then grouped by zone
        Map<UUID, List<ZoneAssignmentService.AssignmentData>> byZone = assignmentViews.describe(site, candidates.stream()
                        .filter(a -> a.getEndDate() == null || !a.getEndDate().isBefore(date))
                        .toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(ZoneAssignmentService.AssignmentData::zoneId));

        return allZones.stream()
                .map(z -> new ZoneStateData(toData(z), byZone.getOrDefault(z.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventData> listAudit(SiteScope site, UUID floorPlanId, UUID zoneId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        return auditService.listForEntity("FloorPlanZone", zoneId);
    }

    private ZoneData toData(FloorPlanZone z) {
        return new ZoneData(
                z.getId(), z.getFloorPlan().getId(),
                z.getName(), z.getDescription(),
                z.getStatus(), z.parsePoints(),
                z.getAreaMSquared(), z.getPerimeterMeters(),
                z.getVersion(), z.getCreatedAt(), z.getUpdatedAt());
    }

    private Map<String, Object> zoneSnapshot(FloorPlanZone z) {
        return Map.of(
                "id", z.getId().toString(),
                "name", z.getName(),
                "status", z.getStatus().name(),
                "description", z.getDescription() != null ? z.getDescription() : ""
        );
    }
}

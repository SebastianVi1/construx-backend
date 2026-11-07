package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class FloorPlanZoneService {

    private final FloorPlanZoneRepository zones;
    private final FloorPlanRepository floorPlans;
    private final FloorPlanCalibrationRepository calibrations;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public FloorPlanZoneService(FloorPlanZoneRepository zones,
                                FloorPlanRepository floorPlans,
                                FloorPlanCalibrationRepository calibrations,
                                ProjectService projects,
                                MembershipGuard guard,
                                DomainAuthorization authorization) {
        this.zones = zones;
        this.floorPlans = floorPlans;
        this.calibrations = calibrations;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record ZoneData(UUID id, UUID floorPlanId, String name, String description,
                           ZoneStatus status, List<double[]> points,
                           double areaMSquared, double perimeterMeters,
                           long version, Instant createdAt, Instant updatedAt) {}

    public record CreateZoneRequest(String name, String description, ZoneStatus status, List<double[]> points) {}

    public record UpdateZoneRequest(String name, String description, ZoneStatus status,
                                    List<double[]> points, long version) {}

    @Transactional
    public ZoneData createZone(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId,
                               CreateZoneRequest request) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        FloorPlan plan = floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

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
        return toData(zone);
    }

    @Transactional
    public ZoneData updateZone(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId,
                               UUID zoneId, UpdateZoneRequest request) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        FloorPlanZone zone = zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));

        try {
            zone.update(request.name(), request.description(),
                    request.status() != null ? request.status() : ZoneStatus.NOT_STARTED,
                    request.points());
            zones.saveAndFlush(zone);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RuleViolationException("ZONE_CONFLICT",
                    "Zone was modified by another user; refresh and retry");
        }
        return toData(zone);
    }

    @Transactional
    public void deleteZone(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId, UUID zoneId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        FloorPlanZone zone = zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));

        zone.softDelete();
        zones.save(zone);
    }

    @Transactional(readOnly = true)
    public List<ZoneData> listZones(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        return zones.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(floorPlanId)
                .stream()
                .map(this::toData)
                .toList();
    }

    private ZoneData toData(FloorPlanZone z) {
        return new ZoneData(
                z.getId(), z.getFloorPlan().getId(),
                z.getName(), z.getDescription(),
                z.getStatus(), z.parsePoints(),
                z.getAreaMSquared(), z.getPerimeterMeters(),
                z.getVersion(), z.getCreatedAt(), z.getUpdatedAt());
    }
}

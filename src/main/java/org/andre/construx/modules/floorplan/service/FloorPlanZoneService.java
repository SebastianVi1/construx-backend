package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.floorplan.repository.ZoneAssignmentRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
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
    private final FloorPlanRepository floorPlans;
    private final FloorPlanCalibrationRepository calibrations;
    private final ZoneAssignmentRepository zoneAssignments;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final AuditService auditService;

    public FloorPlanZoneService(FloorPlanZoneRepository zones,
                                FloorPlanRepository floorPlans,
                                FloorPlanCalibrationRepository calibrations,
                                ZoneAssignmentRepository zoneAssignments,
                                ProjectService projects,
                                MembershipGuard guard,
                                DomainAuthorization authorization,
                                AuditService auditService) {
        this.zones = zones;
        this.floorPlans = floorPlans;
        this.calibrations = calibrations;
        this.zoneAssignments = zoneAssignments;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
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
        auditService.record("FloorPlanZone", zone.getId(), "CREATE",
                member.getId(), null, zoneSnapshot(zone));
        return toData(zone);
    }

    @Transactional
    public ZoneData updateZone(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId,
                               UUID zoneId, UpdateZoneRequest request) {
        OrganizationMembership actor = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
        FloorPlanZone zone = zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));

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
    public void deleteZone(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId, UUID zoneId) {
        OrganizationMembership actor = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        FloorPlanZone zone = zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));

        Map<String, Object> before = zoneSnapshot(zone);
        zone.softDelete();
        zones.save(zone);
        auditService.record("FloorPlanZone", zone.getId(), "DELETE",
                actor.getId(), before, null);
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

    public record ZoneStateData(ZoneData zone, List<ZoneAssignmentService.AssignmentData> activeAssignments) {}

    @Transactional(readOnly = true)
    public List<ZoneStateData> getFloorPlanState(UUID userId, UUID organizationId, UUID projectId,
                                                  UUID floorPlanId, LocalDate date) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        List<FloorPlanZone> allZones = zones.findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(floorPlanId);

        List<ZoneAssignment> candidates = zoneAssignments
                .findByFloorPlanIdAndStartDateLessThanEqualAndClosedAtIsNull(floorPlanId, date);

        Map<UUID, List<ZoneAssignment>> byZone = candidates.stream()
                .filter(a -> a.getEndDate() == null || !a.getEndDate().isBefore(date))
                .collect(java.util.stream.Collectors.groupingBy(a -> a.getZone().getId()));

        return allZones.stream()
                .map(z -> new ZoneStateData(
                        toData(z),
                        byZone.getOrDefault(z.getId(), List.of()).stream()
                                .map(a -> new ZoneAssignmentService.AssignmentData(
                                        a.getId(), a.getZone().getId(),
                                        a.getMembership() != null ? a.getMembership().getId() : null,
                                        a.getCrew() != null ? a.getCrew().getId() : null,
                                        a.getEquipment() != null ? a.getEquipment().getId() : null,
                                        a.getActivity(), a.getStartDate(), a.getEndDate(),
                                        a.getShift(), a.getCreatedAt(), a.getClosedAt()))
                                .toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEventData> listAudit(UUID userId, UUID organizationId, UUID projectId,
                                          UUID floorPlanId, UUID zoneId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
        zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));

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

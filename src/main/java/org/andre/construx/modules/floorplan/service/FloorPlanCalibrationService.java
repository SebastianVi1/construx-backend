package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanCalibration;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class FloorPlanCalibrationService {

    private final FloorPlanCalibrationRepository calibrations;
    private final FloorPlanRepository floorPlans;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public FloorPlanCalibrationService(FloorPlanCalibrationRepository calibrations,
                                       FloorPlanRepository floorPlans,
                                       ProjectService projects,
                                       MembershipGuard guard,
                                       DomainAuthorization authorization) {
        this.calibrations = calibrations;
        this.floorPlans = floorPlans;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record CalibrationData(UUID id, UUID floorPlanId, double metersPerPx,
                                  double pointAX, double pointAY, double pointBX, double pointBY,
                                  double lengthMeters, Instant createdAt) {}

    @Transactional
    public CalibrationData save(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId,
                                double pointAX, double pointAY, double pointBX, double pointBY,
                                double lengthMeters) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        FloorPlan plan = floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        if (!plan.isReady()) {
            throw new RuleViolationException("FLOOR_PLAN_NOT_READY",
                    "Scale calibration requires a READY floor plan");
        }

        double dx = pointBX - pointAX;
        double dy = pointBY - pointAY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1e-9) {
            throw new RuleViolationException("CALIBRATION_ZERO_DISTANCE",
                    "The two calibration points must not be at the same location");
        }

        // Upsert: delete existing calibration if present, then insert new one
        calibrations.deleteByFloorPlanId(floorPlanId);
        calibrations.flush();

        FloorPlanCalibration calibration = new FloorPlanCalibration(plan, member,
                pointAX, pointAY, pointBX, pointBY, lengthMeters);
        calibrations.save(calibration);

        return toData(calibration);
    }

    @Transactional(readOnly = true)
    public CalibrationData get(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));

        return calibrations.findByFloorPlanId(floorPlanId)
                .map(this::toData)
                .orElseThrow(() -> new ResourceNotFoundException("Calibration not found"));
    }

    private CalibrationData toData(FloorPlanCalibration c) {
        return new CalibrationData(c.getId(), c.getFloorPlan().getId(), c.getMetersPerPx(),
                c.getPointAX(), c.getPointAY(), c.getPointBX(), c.getPointBY(),
                c.getLengthMeters(), c.getCreatedAt());
    }
}

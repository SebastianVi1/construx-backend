package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanCalibration;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.SiteScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class FloorPlanCalibrationService {

    private final FloorPlanCalibrationRepository calibrations;
    private final FloorPlanLookup lookup;

    public FloorPlanCalibrationService(FloorPlanCalibrationRepository calibrations, FloorPlanLookup lookup) {
        this.calibrations = calibrations;
        this.lookup = lookup;
    }

    public record CalibrationData(UUID id, UUID floorPlanId, double metersPerPx,
                                  double pointAX, double pointAY, double pointBX, double pointBY,
                                  double lengthMeters, Instant createdAt) {}

    @Transactional
    public CalibrationData save(SiteScope site, UUID floorPlanId,
                                double pointAX, double pointAY, double pointBX, double pointBY,
                                double lengthMeters) {
        site.require(PermissionAction.MANAGE_PLAN);
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);

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

        FloorPlanCalibration calibration = new FloorPlanCalibration(plan, site.membership(),
                pointAX, pointAY, pointBX, pointBY, lengthMeters);
        calibrations.save(calibration);

        return toData(calibration);
    }

    @Transactional(readOnly = true)
    public CalibrationData get(SiteScope site, UUID floorPlanId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.floorPlan(site, floorPlanId);

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

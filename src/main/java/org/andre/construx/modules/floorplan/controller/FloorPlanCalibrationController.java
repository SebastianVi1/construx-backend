package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.andre.construx.modules.floorplan.service.FloorPlanCalibrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.andre.construx.modules.project.access.SiteScope;

import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/calibration")
public class FloorPlanCalibrationController {

    private final FloorPlanCalibrationService calibrations;

    public FloorPlanCalibrationController(FloorPlanCalibrationService calibrations) {
        this.calibrations = calibrations;
    }

    public record SaveCalibrationRequest(
            @NotNull Double pointAX,
            @NotNull Double pointAY,
            @NotNull Double pointBX,
            @NotNull Double pointBY,
            @NotNull @Positive Double lengthMeters
    ) {}

    @PutMapping
    public FloorPlanCalibrationService.CalibrationData save(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @Valid @RequestBody SaveCalibrationRequest body) {
        return calibrations.save(site, floorPlanId,
                body.pointAX(), body.pointAY(), body.pointBX(), body.pointBY(), body.lengthMeters());
    }

    @GetMapping
    public FloorPlanCalibrationService.CalibrationData get(
            SiteScope site,
            @PathVariable UUID floorPlanId) {
        return calibrations.get(site, floorPlanId);
    }

}

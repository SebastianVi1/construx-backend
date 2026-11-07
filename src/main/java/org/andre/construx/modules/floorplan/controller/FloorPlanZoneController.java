package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.service.FloorPlanZoneService;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones")
public class FloorPlanZoneController {

    private final FloorPlanZoneService zoneService;

    public FloorPlanZoneController(FloorPlanZoneService zoneService) {
        this.zoneService = zoneService;
    }

    public record CreateZoneRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            ZoneStatus status,
            @NotNull List<double[]> points
    ) {}

    public record UpdateZoneRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            ZoneStatus status,
            @NotNull List<double[]> points,
            long version
    ) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FloorPlanZoneService.ZoneData create(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @Valid @RequestBody CreateZoneRequest body,
            Authentication authentication) {
        return zoneService.createZone(userId(authentication), organizationId, projectId, floorPlanId,
                new FloorPlanZoneService.CreateZoneRequest(body.name(), body.description(), body.status(), body.points()));
    }

    @GetMapping
    public List<FloorPlanZoneService.ZoneData> list(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            Authentication authentication) {
        return zoneService.listZones(userId(authentication), organizationId, projectId, floorPlanId);
    }

    @PatchMapping("/{zoneId}")
    public FloorPlanZoneService.ZoneData update(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody UpdateZoneRequest body,
            Authentication authentication) {
        return zoneService.updateZone(userId(authentication), organizationId, projectId, floorPlanId, zoneId,
                new FloorPlanZoneService.UpdateZoneRequest(body.name(), body.description(), body.status(), body.points(), body.version()));
    }

    @DeleteMapping("/{zoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            Authentication authentication) {
        zoneService.deleteZone(userId(authentication), organizationId, projectId, floorPlanId, zoneId);
    }

    @GetMapping("/{zoneId}/audit")
    public List<AuditEventData> audit(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            Authentication authentication) {
        return zoneService.listAudit(userId(authentication), organizationId, projectId, floorPlanId, zoneId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.service.FloorPlanZoneService;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import org.andre.construx.shared.security.SiteScope;

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
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @Valid @RequestBody CreateZoneRequest body) {
        return zoneService.createZone(site, floorPlanId,
                new FloorPlanZoneService.CreateZoneRequest(body.name(), body.description(), body.status(), body.points()));
    }

    @GetMapping
    public List<FloorPlanZoneService.ZoneData> list(
            SiteScope site,
            @PathVariable UUID floorPlanId) {
        return zoneService.listZones(site, floorPlanId);
    }

    @PatchMapping("/{zoneId}")
    public FloorPlanZoneService.ZoneData update(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody UpdateZoneRequest body) {
        return zoneService.updateZone(site, floorPlanId, zoneId,
                new FloorPlanZoneService.UpdateZoneRequest(body.name(), body.description(), body.status(), body.points(), body.version()));
    }

    @DeleteMapping("/{zoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId) {
        zoneService.deleteZone(site, floorPlanId, zoneId);
    }

    @GetMapping("/{zoneId}/audit")
    public List<AuditEventData> audit(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId) {
        return zoneService.listAudit(site, floorPlanId, zoneId);
    }

}

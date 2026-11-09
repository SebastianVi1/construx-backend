package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.floorplan.model.AssignmentShift;
import org.andre.construx.modules.floorplan.service.ZoneAssignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import org.andre.construx.modules.project.access.SiteScope;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}")
public class ZoneAssignmentController {

    private final ZoneAssignmentService service;

    public ZoneAssignmentController(ZoneAssignmentService service) {
        this.service = service;
    }

    public record CreateAssignmentRequest(
            UUID membershipId,
            UUID crewId,
            UUID equipmentId,
            @Size(max = 255) String activity,
            LocalDate startDate,
            LocalDate endDate,
            AssignmentShift shift
    ) {}

    public record ReassignRequest(@NotNull UUID newZoneId) {}

    @PostMapping("/zones/{zoneId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneAssignmentService.CreateAssignmentResponse create(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody CreateAssignmentRequest body) {
        return service.createAssignment(site, floorPlanId, zoneId,
                new ZoneAssignmentService.CreateAssignmentRequest(
                        body.membershipId(), body.crewId(), body.equipmentId(),
                        body.activity(), body.startDate(), body.endDate(), body.shift()));
    }

    @GetMapping("/zones/{zoneId}/assignments")
    public List<ZoneAssignmentService.AssignmentData> list(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId) {
        return service.listAssignments(site, floorPlanId, zoneId);
    }

    @PostMapping("/zones/{zoneId}/assignments/{assignmentId}/close")
    public ZoneAssignmentService.AssignmentData close(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @PathVariable UUID assignmentId) {
        return service.closeAssignment(site, floorPlanId, zoneId, assignmentId);
    }

    @PostMapping("/zones/{zoneId}/assignments/{assignmentId}/reassign")
    public ZoneAssignmentService.AssignmentData reassign(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody ReassignRequest body) {
        return service.reassignToZone(site, floorPlanId, zoneId, assignmentId, body.newZoneId());
    }

    /** Active assignments across every zone of the floor plan. */
    @GetMapping("/assignments")
    public List<ZoneAssignmentService.AssignmentData> listAll(
            SiteScope site,
            @PathVariable UUID floorPlanId) {
        return service.listFloorPlanAssignments(site, floorPlanId);
    }

    @GetMapping("/zones/assignment-counts")
    public Map<String, Long> assignmentCounts(
            SiteScope site,
            @PathVariable UUID floorPlanId) {
        return service.getZoneAssignmentCounts(site, floorPlanId);
    }

}

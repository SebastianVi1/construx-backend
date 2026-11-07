package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.floorplan.model.AssignmentShift;
import org.andre.construx.modules.floorplan.service.ZoneAssignmentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ZoneAssignmentController {

    private final ZoneAssignmentService service;

    public ZoneAssignmentController(ZoneAssignmentService service) {
        this.service = service;
    }

    public record CreateAssignmentRequest(
            UUID membershipId,
            UUID crewId,
            UUID equipmentId,
            @NotBlank @Size(max = 255) String activity,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @NotNull AssignmentShift shift
    ) {}

    public record ReassignRequest(@NotNull UUID newZoneId) {}

    @PostMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones/{zoneId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneAssignmentService.CreateAssignmentResponse create(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody CreateAssignmentRequest body,
            Authentication authentication) {
        return service.createAssignment(userId(authentication), organizationId, projectId, floorPlanId, zoneId,
                new ZoneAssignmentService.CreateAssignmentRequest(
                        body.membershipId(), body.crewId(), body.equipmentId(),
                        body.activity(), body.startDate(), body.endDate(), body.shift()));
    }

    @GetMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones/{zoneId}/assignments")
    public List<ZoneAssignmentService.AssignmentData> list(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            Authentication authentication) {
        return service.listAssignments(userId(authentication), organizationId, projectId, floorPlanId, zoneId);
    }

    @PostMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones/{zoneId}/assignments/{assignmentId}/close")
    public ZoneAssignmentService.AssignmentData close(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @PathVariable UUID assignmentId,
            Authentication authentication) {
        return service.closeAssignment(userId(authentication), organizationId, projectId, floorPlanId, zoneId, assignmentId);
    }

    @PostMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones/{zoneId}/assignments/{assignmentId}/reassign")
    public ZoneAssignmentService.AssignmentData reassign(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID zoneId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody ReassignRequest body,
            Authentication authentication) {
        return service.reassignToZone(userId(authentication), organizationId, projectId, floorPlanId, zoneId, assignmentId, body.newZoneId());
    }

    @GetMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/zones/assignment-counts")
    public Map<String, Long> assignmentCounts(
            @PathVariable UUID organizationId,
            @PathVariable UUID projectId,
            @PathVariable UUID floorPlanId,
            Authentication authentication) {
        return service.getZoneAssignmentCounts(userId(authentication), organizationId, projectId, floorPlanId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

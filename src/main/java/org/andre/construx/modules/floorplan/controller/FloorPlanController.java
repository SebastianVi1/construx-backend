package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.floorplan.service.FloorPlanService;
import org.andre.construx.modules.floorplan.service.FloorPlanZoneService;
import org.andre.construx.modules.floorplan.service.FloorPlanZoneService.ZoneStateData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.andre.construx.shared.security.SiteScope;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans")
public class FloorPlanController {

    private final FloorPlanService floorPlans;
    private final FloorPlanZoneService zoneService;

    public FloorPlanController(FloorPlanService floorPlans, FloorPlanZoneService zoneService) {
        this.floorPlans = floorPlans;
        this.zoneService = zoneService;
    }

    public record UploadRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 60) String level,
            @NotNull @Min(1) Integer pageNumber,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Positive Long sizeBytes
    ) {}

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public FloorPlanService.UploadData requestUpload(SiteScope site,
                                                      @Valid @RequestBody UploadRequest body) {
        return floorPlans.requestUpload(site,
                body.name(), body.description(), body.level(),
                body.pageNumber() == null ? 1 : body.pageNumber(),
                body.contentType(), body.sizeBytes());
    }

    @PostMapping("/{floorPlanId}/complete")
    public FloorPlanService.FloorPlanData complete(SiteScope site,
                                                    @PathVariable UUID floorPlanId) {
        return floorPlans.completeUpload(site, floorPlanId);
    }

    @GetMapping
    public List<FloorPlanService.FloorPlanData> list(SiteScope site) {
        return floorPlans.listFloorPlans(site);
    }

    @GetMapping("/{floorPlanId}")
    public FloorPlanService.FloorPlanData get(SiteScope site,
                                               @PathVariable UUID floorPlanId) {
        return floorPlans.getFloorPlan(site, floorPlanId);
    }

    @DeleteMapping("/{floorPlanId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(SiteScope site,
                       @PathVariable UUID floorPlanId) {
        floorPlans.deleteFloorPlan(site, floorPlanId);
    }

    @GetMapping("/{floorPlanId}/state")
    public List<ZoneStateData> state(SiteScope site,
                                     @PathVariable UUID floorPlanId,
                                     @RequestParam(required = false) String date) {
        LocalDate localDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return zoneService.getFloorPlanState(site, floorPlanId, localDate);
    }

}

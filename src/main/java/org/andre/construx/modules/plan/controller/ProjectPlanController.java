package org.andre.construx.modules.plan.controller;


import org.andre.construx.modules.project.access.SiteScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.plan.service.ProjectPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/plans")
public class ProjectPlanController {
    private final ProjectPlanService plans;

    public ProjectPlanController(ProjectPlanService plans) {
        this.plans = plans;
    }

    /** Type and size limits are domain rules (PLAN_FILE_TYPE, PLAN_FILE_TOO_LARGE); here only the shape. */
    public record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotBlank @Size(max = 100) String contentType,
                                @NotNull @Positive Long sizeBytes, @Size(max = 120) String name,
                                @Size(max = 500) String description) {}

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectPlanService.UploadData requestUpload(SiteScope site,
                                                       @Valid @RequestBody UploadRequest body) {
        return plans.requestUpload(site, body.fileName(),
                body.contentType(), body.sizeBytes(), body.name(), body.description());
    }

    @PostMapping("/{planId}/complete")
    public ProjectPlanService.PlanData complete(SiteScope site,
                                                @PathVariable UUID planId) {
        return plans.complete(site, planId);
    }

    @GetMapping
    public List<ProjectPlanService.PlanData> list(SiteScope site) {
        return plans.list(site);
    }

    @GetMapping("/{planId}")
    public ProjectPlanService.PlanData get(SiteScope site,
                                           @PathVariable UUID planId) {
        return plans.get(site, planId);
    }

    @DeleteMapping("/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(SiteScope site, @PathVariable UUID planId) {
        plans.delete(site, planId);
    }
}

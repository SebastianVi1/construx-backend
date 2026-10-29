package org.andre.construx.modules.plan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.plan.service.ProjectPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
    public ProjectPlanService.UploadData requestUpload(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                                       @Valid @RequestBody UploadRequest body,
                                                       Authentication authentication) {
        return plans.requestUpload(userId(authentication), organizationId, projectId, body.fileName(),
                body.contentType(), body.sizeBytes(), body.name(), body.description());
    }

    @PostMapping("/{planId}/complete")
    public ProjectPlanService.PlanData complete(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                                @PathVariable UUID planId, Authentication authentication) {
        return plans.complete(userId(authentication), organizationId, projectId, planId);
    }

    @GetMapping
    public List<ProjectPlanService.PlanData> list(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                                  Authentication authentication) {
        return plans.list(userId(authentication), organizationId, projectId);
    }

    @GetMapping("/{planId}")
    public ProjectPlanService.PlanData get(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                           @PathVariable UUID planId, Authentication authentication) {
        return plans.get(userId(authentication), organizationId, projectId, planId);
    }

    @DeleteMapping("/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID planId,
                       Authentication authentication) {
        plans.delete(userId(authentication), organizationId, projectId, planId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

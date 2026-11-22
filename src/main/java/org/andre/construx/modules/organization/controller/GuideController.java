package org.andre.construx.modules.organization.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.GuideStepStatus;
import org.andre.construx.modules.organization.service.GuideService;
import org.andre.construx.modules.organization.service.GuideService.GuideData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own getting-started guide progress; see docs/guide.md. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/guide")
public class GuideController {
    private final GuideService guides;

    public GuideController(GuideService guides) {
        this.guides = guides;
    }

    @GetMapping
    public GuideData get(OrgScope org) {
        return guides.get(org);
    }

    public record StepUpdate(@NotNull GuideStepStatus status) {}

    public record DismissUpdate(@NotNull Boolean dismissed) {}

    @PutMapping("/steps/{stepId}")
    public GuideData mark(OrgScope org, @PathVariable String stepId, @Valid @RequestBody StepUpdate body) {
        return guides.mark(org, stepId, body.status());
    }

    @PutMapping
    public GuideData dismiss(OrgScope org, @Valid @RequestBody DismissUpdate body) {
        return guides.dismiss(org, body.dismissed());
    }
}

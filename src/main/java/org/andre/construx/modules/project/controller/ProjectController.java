package org.andre.construx.modules.project.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.validation.BudgetUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects")
public class ProjectController {
    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    public record ProjectCreate(@NotBlank @Size(max = 160) String name, @Size(max = 255) String address,
                                LocalDate startDate, LocalDate expectedEndDate, ProjectStatus status) {}

    /** Every field optional; an empty address clears it, and {@code clear} removes the named dates. */
    public record ProjectPatch(@Size(max = 160) String name, @Size(max = 255) String address,
                               LocalDate startDate, LocalDate expectedEndDate, ProjectStatus status,
                               Set<String> clear) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectService.ProjectData create(@PathVariable UUID organizationId, @Valid @RequestBody ProjectCreate body,
                                             Authentication authentication) {
        return projects.create(userId(authentication), organizationId, new ProjectService.ProjectInput(
                body.name(), body.address(), body.startDate(), body.expectedEndDate(), body.status(), null));
    }

    @GetMapping
    public List<ProjectService.ProjectData> list(@PathVariable UUID organizationId,
                                                 @RequestParam(required = false) ProjectStatus status,
                                                 Authentication authentication) {
        return projects.list(userId(authentication), organizationId, status);
    }

    @GetMapping("/{projectId}")
    public ProjectService.ProjectData get(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                          Authentication authentication) {
        return projects.get(userId(authentication), organizationId, projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectService.ProjectData update(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                             @Valid @RequestBody ProjectPatch body, Authentication authentication) {
        return projects.update(userId(authentication), organizationId, projectId, new ProjectService.ProjectInput(
                body.name(), body.address(), body.startDate(), body.expectedEndDate(), body.status(),
                body.clear()));
    }

    @PutMapping("/{projectId}/budget")
    public ProjectService.ProjectData assignBudget(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                                   @Valid @RequestBody BudgetUpdate body,
                                                   Authentication authentication) {
        return projects.assignBudget(userId(authentication), organizationId, projectId,
                body.requireAllocatedBudget());
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

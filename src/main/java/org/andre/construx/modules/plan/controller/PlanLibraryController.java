package org.andre.construx.modules.plan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.plan.service.PlanLibraryService;
import org.andre.construx.modules.plan.service.ProjectPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
public class PlanLibraryController {
    private final PlanLibraryService library;
    public PlanLibraryController(PlanLibraryService library) { this.library = library; }
    public record FolderRequest(@NotBlank @Size(max = 120) String name, UUID parentId) {}
    public record MoveRequest(UUID folderId) {}

    @GetMapping("/plan-library")
    public PlanLibraryService.LibraryData library(@PathVariable UUID organizationId, Authentication auth) {
        return library.library(user(auth), organizationId);
    }

    @PostMapping("/plan-folders")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanLibraryService.FolderData create(@PathVariable UUID organizationId,
            @Valid @RequestBody FolderRequest body, Authentication auth) {
        return library.create(user(auth), organizationId, body.name(), body.parentId());
    }

    /** Full name/parent replacement: null parent means the library root. */
    @PatchMapping("/plan-folders/{folderId}")
    public PlanLibraryService.FolderData update(@PathVariable UUID organizationId, @PathVariable UUID folderId,
            @Valid @RequestBody FolderRequest body, Authentication auth) {
        return library.update(user(auth), organizationId, folderId, body.name(), body.parentId());
    }

    @DeleteMapping("/plan-folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID folderId, Authentication auth) {
        library.delete(user(auth), organizationId, folderId);
    }

    @PutMapping("/projects/{projectId}/plans/{planId}/folder")
    public ProjectPlanService.PlanData move(@PathVariable UUID organizationId, @PathVariable UUID projectId,
            @PathVariable UUID planId, @Valid @RequestBody MoveRequest body, Authentication auth) {
        return library.movePlan(user(auth), organizationId, projectId, planId, body.folderId());
    }

    private UUID user(Authentication auth) { return UUID.fromString(auth.getName()); }
}

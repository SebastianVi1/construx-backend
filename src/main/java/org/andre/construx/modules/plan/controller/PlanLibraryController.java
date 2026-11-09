package org.andre.construx.modules.plan.controller;


import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.plan.service.PlanLibraryService;
import org.andre.construx.modules.plan.service.ProjectPlanService;
import org.springframework.http.HttpStatus;
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
    public PlanLibraryService.LibraryData library(OrgScope org) {
        return library.library(org);
    }

    @PostMapping("/plan-folders")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanLibraryService.FolderData create(OrgScope org,
            @Valid @RequestBody FolderRequest body) {
        return library.create(org, body.name(), body.parentId());
    }

    /** Full name/parent replacement: null parent means the library root. */
    @PatchMapping("/plan-folders/{folderId}")
    public PlanLibraryService.FolderData update(OrgScope org, @PathVariable UUID folderId,
            @Valid @RequestBody FolderRequest body) {
        return library.update(org, folderId, body.name(), body.parentId());
    }

    @DeleteMapping("/plan-folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(OrgScope org, @PathVariable UUID folderId) {
        library.delete(org, folderId);
    }

    @PutMapping("/projects/{projectId}/plans/{planId}/folder")
    public ProjectPlanService.PlanData move(SiteScope site,
            @PathVariable UUID planId, @Valid @RequestBody MoveRequest body) {
        return library.movePlan(site, planId, body.folderId());
    }

}

package org.andre.construx.modules.project.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.project.service.ProjectAccessService;
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
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/access")
public class ProjectAccessController {
    private final ProjectAccessService accesses;

    public ProjectAccessController(ProjectAccessService accesses) {
        this.accesses = accesses;
    }

    public record AccessInput(@NotNull UUID membershipId) {}

    public record RoleSummary(String code, String name) {}

    public record AccessResponse(UUID accessId, UUID membershipId, UUID userId, String fullName, RoleSummary role) {
        static AccessResponse from(ProjectAccessService.AccessData data) {
            return new AccessResponse(data.accessId(), data.membershipId(), data.userId(), data.fullName(),
                    new RoleSummary(data.roleCode(), data.roleName()));
        }
    }

    @GetMapping
    public List<AccessResponse> list(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                     Authentication authentication) {
        return accesses.list(userId(authentication), organizationId, projectId).stream()
                .map(AccessResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccessResponse add(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                              @Valid @RequestBody AccessInput body, Authentication authentication) {
        return AccessResponse.from(accesses.add(userId(authentication), organizationId, projectId, body.membershipId()));
    }

    @DeleteMapping("/{accessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID organizationId, @PathVariable UUID projectId, @PathVariable UUID accessId,
                       Authentication authentication) {
        accesses.remove(userId(authentication), organizationId, projectId, accessId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

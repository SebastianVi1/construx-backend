package org.andre.construx.modules.organization.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.service.InvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/invitations")
public class InvitationController {
    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    public record InvitationInput(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(max = 64) String roleCode, UUID projectId) {}

    public record RoleSummary(String code, String name) {}

    public record InvitationResponse(UUID id, String email, RoleSummary role, UUID projectId,
                                     InvitationStatus status, Instant expiresAt) {
        static InvitationResponse from(InvitationService.InvitationData data) {
            return new InvitationResponse(data.id(), data.email(), new RoleSummary(data.roleCode(), data.roleName()),
                    data.projectId(), data.status(), data.expiresAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse create(@PathVariable UUID organizationId, @Valid @RequestBody InvitationInput body,
                                     Authentication authentication) {
        return InvitationResponse.from(invitations.create(userId(authentication), organizationId,
                body.email(), body.roleCode(), body.projectId()));
    }

    @GetMapping
    public List<InvitationResponse> list(@PathVariable UUID organizationId,
                                         @RequestParam(defaultValue = "PENDING") InvitationStatus status,
                                         Authentication authentication) {
        return invitations.list(userId(authentication), organizationId, status).stream()
                .map(InvitationResponse::from).toList();
    }

    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID organizationId, @PathVariable UUID invitationId,
                       Authentication authentication) {
        invitations.revoke(userId(authentication), organizationId, invitationId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

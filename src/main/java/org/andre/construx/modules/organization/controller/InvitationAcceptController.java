package org.andre.construx.modules.organization.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.organization.service.InvitationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Not under an organization path: the emailed token identifies the tenant. */
@RestController
@RequestMapping("/api/invitations")
public class InvitationAcceptController {
    private final InvitationService invitations;

    public InvitationAcceptController(InvitationService invitations) {
        this.invitations = invitations;
    }

    public record AcceptInput(@NotBlank @Size(max = 200) String token) {}

    public record OrganizationSummary(UUID id, String name) {}

    public record AcceptResponse(OrganizationSummary organization, UUID membershipId,
                                 InvitationController.RoleSummary role, UUID projectId) {}

    @PostMapping("/accept")
    public AcceptResponse accept(@Valid @RequestBody AcceptInput body, Authentication authentication) {
        InvitationService.AcceptedData data = invitations.accept(UUID.fromString(authentication.getName()), body.token());
        return new AcceptResponse(new OrganizationSummary(data.organizationId(), data.organizationName()),
                data.membershipId(), new InvitationController.RoleSummary(data.roleCode(), data.roleName()),
                data.projectId());
    }
}

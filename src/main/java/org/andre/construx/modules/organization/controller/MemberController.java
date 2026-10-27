package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    public record PermissionResponse(PermissionAction action, PermissionScope scope) {}

    public record MeResponse(UUID membershipId, OrganizationController.RoleSummary role,
                             List<PermissionResponse> permissions) {}

    public record MemberResponse(UUID membershipId, UUID userId, String email, String fullName,
                                 OrganizationController.RoleSummary role, MembershipStatus status) {}

    public record RoleChange(@NotBlank String roleCode) {}

    @GetMapping("/me")
    public MeResponse me(@PathVariable UUID organizationId, Authentication authentication) {
        MemberService.MeData me = members.me(userId(authentication), organizationId);
        return new MeResponse(me.membershipId(), new OrganizationController.RoleSummary(me.roleCode(), me.roleName()),
                me.permissions().stream().map(p -> new PermissionResponse(p.action(), p.scope())).toList());
    }

    @GetMapping("/roles")
    public List<MemberService.RoleData> roles(@PathVariable UUID organizationId, Authentication authentication) {
        return members.roles(userId(authentication), organizationId);
    }

    @GetMapping("/members")
    public List<MemberResponse> list(@PathVariable UUID organizationId,
                                     @RequestParam(defaultValue = "ACTIVE") MembershipStatus status,
                                     Authentication authentication) {
        return members.members(userId(authentication), organizationId, status).stream()
                .map(MemberController::toResponse)
                .toList();
    }

    @PatchMapping("/members/{membershipId}")
    public MemberResponse changeRole(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
                                     @Valid @RequestBody RoleChange body, Authentication authentication) {
        return toResponse(members.changeRole(userId(authentication), organizationId, membershipId, body.roleCode()));
    }

    private static MemberResponse toResponse(MemberService.MemberData m) {
        return new MemberResponse(m.membershipId(), m.userId(), m.email(), m.fullName(),
                new OrganizationController.RoleSummary(m.roleCode(), m.roleName()), m.status());
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

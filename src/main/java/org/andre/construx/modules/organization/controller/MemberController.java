package org.andre.construx.modules.organization.controller;


import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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
                                 OrganizationController.RoleSummary role, MembershipStatus status, URI avatarUrl) {}

    public record RoleChange(@NotBlank String roleCode) {}

    public record StatusChange(@NotNull MembershipStatus status) {}

    @GetMapping("/me")
    public MeResponse me(OrgScope org) {
        MemberService.MeData me = members.me(org);
        return new MeResponse(me.membershipId(), new OrganizationController.RoleSummary(me.roleCode(), me.roleName()),
                me.permissions().stream().map(p -> new PermissionResponse(p.action(), p.scope())).toList());
    }

    @GetMapping("/roles")
    public List<MemberService.RoleData> roles(OrgScope org) {
        return members.roles(org);
    }

    @GetMapping("/members")
    public List<MemberResponse> list(OrgScope org,
                                     @RequestParam(defaultValue = "ACTIVE") MembershipStatus status) {
        return members.members(org, status).stream()
                .map(MemberController::toResponse)
                .toList();
    }

    @PatchMapping("/members/{membershipId}")
    public MemberResponse changeRole(OrgScope org, @PathVariable UUID membershipId,
                                     @Valid @RequestBody RoleChange body) {
        return toResponse(members.changeRole(org, membershipId, body.roleCode()));
    }

    @PatchMapping("/members/{membershipId}/status")
    public MemberResponse setStatus(OrgScope org, @PathVariable UUID membershipId,
                                    @Valid @RequestBody StatusChange body) {
        return switch (body.status()) {
            case ACTIVE -> toResponse(members.reactivate(org, membershipId));
            case SUSPENDED -> toResponse(members.suspend(org, membershipId));
            case LEFT -> throw new IllegalArgumentException("Use DELETE to remove a member");
        };
    }

    @DeleteMapping("/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(OrgScope org, @PathVariable UUID membershipId) {
        members.removeMember(org, membershipId);
    }

    private static MemberResponse toResponse(MemberService.MemberData m) {
        return new MemberResponse(m.membershipId(), m.userId(), m.email(), m.fullName(),
                new OrganizationController.RoleSummary(m.roleCode(), m.roleName()), m.status(),
                m.avatarUrl());
    }
}

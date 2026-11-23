package org.andre.construx.modules.organization.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.service.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A company's Organization roles (contract roles §5.1, §5.2). Readable with MANAGE_ROLES or MANAGE_MEMBERS. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/roles")
public class RoleController {
    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    /** The list's fields plus {@code permissions} and {@code defaults} (null unless Built-in). */
    public record RoleDetailResponse(@JsonUnwrapped RoleService.RoleData role,
                                     List<RoleService.GrantData> permissions,
                                     List<RoleService.GrantData> defaults) {}

    @GetMapping
    public List<RoleService.RoleData> list(OrgScope org) {
        return roles.list(org);
    }

    @GetMapping("/{code}")
    public RoleDetailResponse get(OrgScope org, @PathVariable String code) {
        RoleService.RoleDetailData detail = roles.get(org, code);
        return new RoleDetailResponse(detail.role(), detail.permissions(), detail.defaults());
    }
}

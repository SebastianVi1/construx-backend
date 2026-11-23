package org.andre.construx.modules.organization.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.service.RoleDraft;
import org.andre.construx.modules.organization.service.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A company's Organization roles (contract roles §5.1 to §5.6). Readable with MANAGE_ROLES or
 * MANAGE_MEMBERS; written with MANAGE_ROLES under the "never above your own role" rule.
 */
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

    /** §5.3: a new Custom role; the server picks its code. */
    public record RoleCreateRequest(String name, String description, @NotNull List<RoleDraft.Grant> permissions) {}

    /** §5.4: the full permission set, plus the role's {@code version} as the client last read it. */
    public record RoleUpdateRequest(String name, String description,
                                    @NotNull List<RoleDraft.Grant> permissions, @NotNull Long version) {}

    /** §5.5: the role's {@code version} as the client last read it. */
    public record RestoreRequest(@NotNull Long version) {}

    @GetMapping
    public List<RoleService.RoleData> list(OrgScope org) {
        return roles.list(org);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDetailResponse create(OrgScope org, @Valid @RequestBody RoleCreateRequest body) {
        RoleDraft draft = RoleDraft.of(body.name(), body.description(), body.permissions());
        return response(roles.create(org, draft));
    }

    @GetMapping("/{code}")
    public RoleDetailResponse get(OrgScope org, @PathVariable String code) {
        return response(roles.get(org, code));
    }

    @PutMapping("/{code}")
    public RoleDetailResponse update(OrgScope org, @PathVariable String code,
                                     @Valid @RequestBody RoleUpdateRequest body) {
        RoleDraft draft = RoleDraft.of(body.name(), body.description(), body.permissions());
        return response(roles.update(org, code, draft, body.version()));
    }

    @PostMapping("/{code}/restore-defaults")
    public RoleDetailResponse restore(OrgScope org, @PathVariable String code,
                                      @Valid @RequestBody RestoreRequest body) {
        return response(roles.restore(org, code, body.version()));
    }

    /** §5.6: a Custom role; {@code replacement} is required while anyone or any invitation still uses it. */
    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(OrgScope org, @PathVariable String code,
                       @RequestParam(required = false) String replacement) {
        roles.delete(org, code, replacement);
    }

    private static RoleDetailResponse response(RoleService.RoleDetailData detail) {
        return new RoleDetailResponse(detail.role(), detail.permissions(), detail.defaults());
    }
}

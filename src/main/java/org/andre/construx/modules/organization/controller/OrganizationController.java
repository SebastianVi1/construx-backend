package org.andre.construx.modules.organization.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import static org.andre.construx.shared.security.SecurityUtils.userId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.organization.model.OrganizationStatus;
import org.andre.construx.modules.organization.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    public record OrganizationInput(@NotBlank @Size(max = 160) String name) {}

    public record RoleSummary(String code, String name) {}

    public record OrganizationResponse(UUID id, String name, OrganizationStatus status, RoleSummary myRole) {
        static OrganizationResponse from(OrganizationService.OrganizationData data) {
            return new OrganizationResponse(data.id(), data.name(), data.status(),
                    new RoleSummary(data.myRoleCode(), data.myRoleName()));
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(@Valid @RequestBody OrganizationInput body, Authentication authentication) {
        return OrganizationResponse.from(organizations.create(userId(authentication), body.name()));
    }

    @GetMapping
    public List<OrganizationResponse> listMine(Authentication authentication) {
        return organizations.listMine(userId(authentication)).stream().map(OrganizationResponse::from).toList();
    }

    @GetMapping("/{organizationId}")
    public OrganizationResponse get(OrgScope org) {
        return OrganizationResponse.from(organizations.get(org));
    }

    @PatchMapping("/{organizationId}")
    public OrganizationResponse rename(OrgScope org,
                                       @Valid @RequestBody OrganizationInput body) {
        return OrganizationResponse.from(organizations.rename(org, body.name()));
    }

    @DeleteMapping("/{organizationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(OrgScope org) {
        organizations.delete(org);
    }
}

package org.andre.construx.modules.project.controller;


import org.andre.construx.modules.project.access.SiteScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.project.model.LaborRateAssignment;
import org.andre.construx.modules.project.model.LaborRateKind;
import org.andre.construx.modules.project.service.LaborRateService;
import org.andre.construx.modules.project.service.LaborRateService.LaborRate;
import org.andre.construx.modules.project.service.LaborRateService.LaborRateView;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.modules.project.service.ProjectAccessService.SiteMember;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/access")
public class ProjectAccessController {
    private final ProjectAccessService accesses;
    private final LaborRateService laborRates;

    public ProjectAccessController(ProjectAccessService accesses, LaborRateService laborRates) {
        this.accesses = accesses;
        this.laborRates = laborRates;
    }

    public record AccessInput(@NotNull UUID membershipId) {}

    public record RoleSummary(String code, String name) {}

    /** {@code laborRate} is null unless the caller holds VIEW_BUDGET or MANAGE_PROJECT on the site. */
    public record AccessResponse(UUID accessId, UUID membershipId, UUID userId, String fullName, RoleSummary role,
                                 URI avatarUrl, LaborRate laborRate) {
        static AccessResponse from(SiteMember member) {
            ProjectAccessService.AccessData data = member.access();
            return new AccessResponse(data.accessId(), data.membershipId(), data.userId(), data.fullName(),
                    new RoleSummary(data.roleCode(), data.roleName()),
                    data.avatarUrl(), member.laborRate());
        }
    }

    /** {@code laborCategoryId} for CATALOG, {@code dailyAmount} (two decimals) for FIXED, nothing for NONE. */
    public record LaborRateBody(LaborRateKind kind, UUID laborCategoryId, BigDecimal dailyAmount) {}

    @GetMapping
    public List<AccessResponse> list(SiteScope site) {
        return accesses.list(site).stream()
                .map(AccessResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccessResponse add(SiteScope site,
                              @Valid @RequestBody AccessInput body) {
        return AccessResponse.from(accesses.add(site, body.membershipId()));
    }

    @GetMapping("/{accessId}/labor-rate")
    public LaborRateView laborRate(SiteScope site, @PathVariable UUID accessId) {
        return laborRates.read(site, accessId);
    }

    @PutMapping("/{accessId}/labor-rate")
    public AccessResponse setLaborRate(SiteScope site, @PathVariable UUID accessId,
                                       @RequestBody LaborRateBody body) {
        return AccessResponse.from(accesses.setLaborRate(site, accessId,
                new LaborRateAssignment.Choice(body.kind(), body.laborCategoryId(), body.dailyAmount())));
    }

    @DeleteMapping("/{accessId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(SiteScope site, @PathVariable UUID accessId) {
        accesses.remove(site, accessId);
    }
}

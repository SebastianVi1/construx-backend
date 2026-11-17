package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.service.CatalogService;
import org.andre.construx.modules.pricing.service.CatalogService.CatalogSummary;
import org.andre.construx.modules.pricing.service.CatalogService.FamilyData;
import org.andre.construx.modules.pricing.service.CatalogService.UnitData;
import org.andre.construx.modules.pricing.service.ResourceCompositionService;
import org.andre.construx.modules.pricing.service.ResourceCompositionService.StandbyPercentage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Catalog summary, Units and Families (docs/pricing.md §2.1, §2.2). */
@RestController
@RequestMapping("/api/organizations/{organizationId}")
public class CatalogController {
    private final CatalogService catalog;
    private final ResourceCompositionService composition;

    public CatalogController(CatalogService catalog, ResourceCompositionService composition) {
        this.catalog = catalog;
        this.composition = composition;
    }

    /** Standby and reserve shares per equipment charge, read-only in v1 (docs/pricing.md §2.6). */
    @GetMapping("/equipment-standby-percentages")
    public List<StandbyPercentage> standbyPercentages(OrgScope org) {
        return composition.standbyPercentages(org);
    }

    public record UnitCreate(@NotBlank @Size(max = 20) String code, @Size(max = 120) String description) {}

    /** The code is immutable; an empty description clears it. */
    public record UnitPatch(@Size(max = 120) String description) {}

    public record FamilyCreate(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 160) String name) {}

    public record FamilyPatch(@Size(max = 160) String name) {}

    @GetMapping("/catalog")
    public CatalogSummary summary(OrgScope org) {
        return catalog.summary(org);
    }

    @GetMapping("/units")
    public List<UnitData> units(OrgScope org, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return catalog.units(org, includeArchived);
    }

    @GetMapping("/families")
    public List<FamilyData> families(OrgScope org, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return catalog.families(org, includeArchived);
    }

    @PostMapping("/units")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitData createUnit(OrgScope org, @Valid @RequestBody UnitCreate body) {
        return catalog.createUnit(org, body.code(), body.description());
    }

    @PatchMapping("/units/{unitId}")
    public UnitData updateUnit(OrgScope org, @PathVariable UUID unitId, @Valid @RequestBody UnitPatch body) {
        return catalog.updateUnit(org, unitId, body.description());
    }

    @PostMapping("/units/{unitId}/archive")
    public UnitData archiveUnit(OrgScope org, @PathVariable UUID unitId) {
        return catalog.archiveUnit(org, unitId);
    }

    @PostMapping("/families")
    @ResponseStatus(HttpStatus.CREATED)
    public FamilyData createFamily(OrgScope org, @Valid @RequestBody FamilyCreate body) {
        return catalog.createFamily(org, body.code(), body.name());
    }

    @PatchMapping("/families/{familyId}")
    public FamilyData updateFamily(OrgScope org, @PathVariable UUID familyId, @Valid @RequestBody FamilyPatch body) {
        return catalog.updateFamily(org, familyId, body.name());
    }

    @PostMapping("/families/{familyId}/archive")
    public FamilyData archiveFamily(OrgScope org, @PathVariable UUID familyId) {
        return catalog.archiveFamily(org, familyId);
    }
}

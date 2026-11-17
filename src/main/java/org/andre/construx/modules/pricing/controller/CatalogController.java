package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.service.CatalogService;
import org.andre.construx.modules.pricing.service.CatalogService.CatalogSummary;
import org.andre.construx.modules.pricing.service.CatalogService.FamilyData;
import org.andre.construx.modules.pricing.service.CatalogService.UnitData;
import org.andre.construx.modules.pricing.service.ResourceCompositionService;
import org.andre.construx.modules.pricing.service.ResourceCompositionService.StandbyPercentage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}

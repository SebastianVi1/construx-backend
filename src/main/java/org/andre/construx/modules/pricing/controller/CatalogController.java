package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.service.CatalogService;
import org.andre.construx.modules.pricing.service.CatalogService.CatalogSummary;
import org.andre.construx.modules.pricing.service.CatalogService.FamilyData;
import org.andre.construx.modules.pricing.service.CatalogService.UnitData;
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

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
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

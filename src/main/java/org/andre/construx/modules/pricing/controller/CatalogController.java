package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalog summary, Units and Families; read only until the Unit and Family writes arrive. */
@RestController
@RequestMapping("/api/organizations/{organizationId}")
public class CatalogController {
    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    public CatalogService.CatalogSummary summary(OrgScope org) {
        return catalog.summary(org);
    }

    @GetMapping("/units")
    public List<CatalogService.UnitData> units(OrgScope org,
                                               @RequestParam(defaultValue = "false") boolean includeArchived) {
        return catalog.units(org, includeArchived);
    }

    @GetMapping("/families")
    public List<CatalogService.FamilyData> families(OrgScope org,
                                                    @RequestParam(defaultValue = "false") boolean includeArchived) {
        return catalog.families(org, includeArchived);
    }
}

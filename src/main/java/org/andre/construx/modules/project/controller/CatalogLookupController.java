package org.andre.construx.modules.project.controller;

import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.service.CatalogLookupService;
import org.andre.construx.modules.project.service.CatalogLookupService.ItemPage;
import org.andre.construx.modules.project.service.CatalogLookupService.Purpose;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Site's catalog lookup, docs/pricing.md §6.7. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/catalog-lookup")
public class CatalogLookupController {
    private final CatalogLookupService lookup;

    public CatalogLookupController(CatalogLookupService lookup) {
        this.lookup = lookup;
    }

    @GetMapping
    public ItemPage lookup(SiteScope site, @RequestParam Purpose purpose,
                           @RequestParam(required = false) String query,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        return lookup.lookup(site, purpose, query, page, size);
    }
}

package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.CatalogPage;
import org.andre.construx.modules.pricing.service.ResourceService;
import org.andre.construx.modules.pricing.service.ResourceService.CostData;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceData;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceDetail;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The Catalog's Resources, read side (docs/pricing.md §2.4). */
@RestController
@RequestMapping("/api/organizations/{organizationId}/resources")
public class ResourceController {
    private final ResourceService resources;

    public ResourceController(ResourceService resources) {
        this.resources = resources;
    }

    @GetMapping
    public CatalogPage<ResourceData> list(OrgScope org,
                                          @RequestParam(required = false) List<ResourceType> type,
                                          @RequestParam(required = false) String query,
                                          @RequestParam(required = false) UUID familyId,
                                          @RequestParam(required = false) UUID unitId,
                                          @RequestParam(required = false) UUID supplierId,
                                          @RequestParam(required = false) Boolean composite,
                                          @RequestParam(defaultValue = "false") boolean includeArchived,
                                          @RequestParam(defaultValue = "false") boolean missingFromImport,
                                          @RequestParam(required = false) Boolean discontinued,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String sort) {
        return resources.list(org, new ResourceFilter(type, query, familyId, unitId, supplierId, composite,
                includeArchived, missingFromImport, discontinued), page, size, sort);
    }

    @GetMapping("/{resourceId}")
    public ResourceDetail get(OrgScope org, @PathVariable UUID resourceId) {
        return resources.get(org, resourceId);
    }

    @GetMapping("/{resourceId}/costs")
    public CatalogPage<CostData> costs(OrgScope org, @PathVariable UUID resourceId,
                                       @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return resources.costs(org, resourceId, from, to, page, size);
    }
}

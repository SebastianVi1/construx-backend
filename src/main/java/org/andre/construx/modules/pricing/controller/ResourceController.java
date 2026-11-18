package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.PageData;
import org.andre.construx.modules.pricing.service.ResourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Catalog Resources and their Cost history; read only until the Resource writes arrive. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/resources")
public class ResourceController {
    private final ResourceService resources;

    public ResourceController(ResourceService resources) {
        this.resources = resources;
    }

    @GetMapping
    public PageData<ResourceService.ResourceData> list(OrgScope org,
                                                       @RequestParam(name = "type", required = false) List<ResourceType> types,
                                                       @RequestParam(required = false) String query,
                                                       @RequestParam(required = false) UUID familyId,
                                                       @RequestParam(required = false) UUID unitId,
                                                       @RequestParam(required = false) UUID supplierId,
                                                       @RequestParam(required = false) Boolean composite,
                                                       @RequestParam(defaultValue = "false") boolean includeArchived,
                                                       @RequestParam(required = false) Boolean missingFromImport,
                                                       @RequestParam(required = false) Boolean discontinued,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size,
                                                       @RequestParam(required = false) String sort) {
        return resources.list(org, new ResourceService.ResourceFilter(types, query, familyId, unitId, supplierId,
                composite, includeArchived, missingFromImport, discontinued), page, size, sort);
    }

    @GetMapping("/{resourceId}")
    public ResourceService.ResourceDetail get(OrgScope org, @PathVariable UUID resourceId) {
        return resources.get(org, resourceId);
    }

    @GetMapping("/{resourceId}/costs")
    public PageData<ResourceService.CostEntry> costs(OrgScope org, @PathVariable UUID resourceId,
                                                     @RequestParam(required = false) Instant from,
                                                     @RequestParam(required = false) Instant to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return resources.costs(org, resourceId, from, to, page, size);
    }
}

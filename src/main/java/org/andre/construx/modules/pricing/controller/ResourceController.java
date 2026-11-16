package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.CatalogPage;
import org.andre.construx.modules.pricing.service.ResourceService;
import org.andre.construx.modules.pricing.service.ResourceService.CostData;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceData;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceDetail;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceFilter;
import org.andre.construx.modules.pricing.service.ResourceWriteService;
import org.andre.construx.modules.pricing.service.ResourceWriteService.CostEntry;
import org.andre.construx.modules.pricing.service.ResourceWriteService.ResourceCreate;
import org.andre.construx.modules.pricing.service.ResourceWriteService.ResourcePatch;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Catalog's Resources and their Cost history (docs/pricing.md §2.4, §2.5). */
@RestController
@RequestMapping("/api/organizations/{organizationId}/resources")
public class ResourceController {
    private final ResourceService resources;
    private final ResourceWriteService writes;

    public ResourceController(ResourceService resources, ResourceWriteService writes) {
        this.resources = resources;
        this.writes = writes;
    }

    /** Money travels as a string with two decimals; {@code laborDetail} is read only to be refused here. */
    public record CreateBody(ResourceType type, String code, String name, String description, UUID unitId,
                             UUID familyId, UUID supplierId, String brand, BigDecimal cost, Boolean isComposite,
                             Map<String, Object> laborDetail) {}

    public record CostBody(BigDecimal amount, UUID supplierId, String sku, String note) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceDetail create(OrgScope org, @RequestBody CreateBody body) {
        return writes.create(org, new ResourceCreate(body.type(), body.code(), body.name(), body.description(),
                body.unitId(), body.familyId(), body.supplierId(), body.brand(), body.cost(), body.isComposite(),
                body.laborDetail() != null));
    }

    @PatchMapping("/{resourceId}")
    public ResourceDetail update(OrgScope org, @PathVariable UUID resourceId, @RequestBody ResourcePatch body) {
        return writes.update(org, resourceId, body);
    }

    @PostMapping("/{resourceId}/archive")
    public ResourceDetail archive(OrgScope org, @PathVariable UUID resourceId) {
        return writes.archive(org, resourceId);
    }

    @PostMapping("/{resourceId}/costs")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceDetail recordCost(OrgScope org, @PathVariable UUID resourceId, @RequestBody CostBody body) {
        return writes.recordCost(org, resourceId, new CostEntry(body.amount(), body.supplierId(), body.sku(),
                body.note()));
    }

    /** Cost history entries are immutable (ADR 0009): no route edits or deletes one. */
    @RequestMapping(path = "/{resourceId}/costs/{costId}",
            method = {RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void historyIsImmutable(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of());
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

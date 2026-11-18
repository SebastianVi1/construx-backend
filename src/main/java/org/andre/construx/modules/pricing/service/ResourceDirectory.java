package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog lookups for other modules (finance, crew, project): a Resource of the organization and its current cost, with no
 * permission check. Callers authorize; every lookup is filtered by organization, so another tenant's id is not found.
 */
@Service
public class ResourceDirectory {
    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository costs;

    public ResourceDirectory(ResourceRepository resources, ResourceCostHistoryRepository costs) {
        this.resources = resources;
        this.costs = costs;
    }

    /** One Cost history row. */
    public record CostEntry(UUID resourceId, BigDecimal amount, Instant effectiveFrom) {}

    /** The whole Cost history of these Resources of the organization, in no particular order. */
    @Transactional(readOnly = true)
    public List<CostEntry> costHistory(UUID organizationId, Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return costs.findOfResources(organizationId, resourceIds).stream()
                .map(h -> new CostEntry(h.getResourceId(), h.getAmount(), h.getEffectiveFrom())).toList();
    }

    /** {@code currentCost} is null until the Resource has a cost. */
    public record CatalogResource(UUID id, String code, String name, ResourceType type, String unitCode,
                                  BigDecimal currentCost, boolean archived, boolean composite) {}

    /** The Resource inside the organization, or 404. */
    @Transactional(readOnly = true)
    public CatalogResource get(UUID organizationId, UUID resourceId) {
        return resources.findInOrganization(resourceId, organizationId).map(ResourceDirectory::toRef)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    /** The Resources of the organization among {@code ids}, by id; unknown ids are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, CatalogResource> byIds(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resources.findAllInOrganization(ids, organizationId).stream().map(ResourceDirectory::toRef)
                .collect(Collectors.toMap(CatalogResource::id, Function.identity()));
    }

    /** A Resource as the Site's catalog lookup shows it: no cost, wage or supplier (docs/pricing.md §6.7). */
    public record LookupItem(UUID id, String code, String name, ResourceType type, String unitCode) {}

    /**
     * One page of the simple, non-archived Resources of these types, by code; {@code withCost} keeps only those
     * with a cost above zero. {@code query} matches code or name; {@code page}/{@code size} as §0, otherwise an
     * {@link IllegalArgumentException} (400 INVALID_ARGUMENT outside the pricing controllers).
     */
    @Transactional(readOnly = true)
    public CatalogPage<LookupItem> lookup(UUID organizationId, Collection<ResourceType> types, boolean withCost,
                                          String query, Integer page, Integer size) {
        Pageable pageable;
        try {
            pageable = CatalogPage.request(page, size, null, Set.of(), Sort.by("code"), "code", "id");
        } catch (PricingValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return CatalogPage.of(resources.lookup(organizationId, types, withCost, CatalogSearch.containsPattern(query),
                pageable), r -> new LookupItem(r.getId(), r.getCode(), r.getName(), r.getType(), r.getUnit().getCode()));
    }

    private static CatalogResource toRef(Resource r) {
        return new CatalogResource(r.getId(), r.getCode(), r.getName(), r.getType(), r.getUnit().getCode(),
                r.getCurrentCost(), r.isArchived(), r.isComposite());
    }
}

package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
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

    public ResourceDirectory(ResourceRepository resources) {
        this.resources = resources;
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

    private static CatalogResource toRef(Resource r) {
        return new CatalogResource(r.getId(), r.getCode(), r.getName(), r.getType(), r.getUnit().getCode(),
                r.getCurrentCost(), r.isArchived(), r.isComposite());
    }
}

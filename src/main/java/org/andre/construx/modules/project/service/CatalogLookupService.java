package org.andre.construx.modules.project.service;

import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.CatalogPage;
import org.andre.construx.modules.pricing.service.ResourceDirectory;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The Site's catalog lookup (docs/pricing.md §6.7): codes and names of Resources, never costs, so whoever sets a
 * Labor rate or records an expense on the Site can pick a Resource without VIEW_PRICES.
 */
@Service
public class CatalogLookupService {
    private final ResourceDirectory catalog;
    private final ActivityRepository activities;

    public CatalogLookupService(ResourceDirectory catalog, ActivityRepository activities) {
        this.catalog = catalog;
        this.activities = activities;
    }

    /** What the Resource will be picked for, which decides both the permission and the Resources listed. */
    public enum Purpose {
        /** A Labor category for a Labor rate: simple, non-archived LABOR Resources. Needs MANAGE_PROJECT. */
        LABOR_RATE,
        /**
         * A Resource for an expense from the catalog: simple, non-archived MATERIAL, LABOR and EQUIPMENT Resources
         * with a cost. Needs MANAGE_BUDGET or being the Lead of a task of the Site.
         */
        EXPENSE
    }

    public record Item(UUID id, String code, String name, ResourceType type, String unitCode) {}

    public record ItemPage(List<Item> content, int page, int size, long totalElements, int totalPages) {}

    @Transactional(readOnly = true)
    public ItemPage lookup(SiteScope site, Purpose purpose, String query, Integer page, Integer size) {
        CatalogPage<ResourceDirectory.LookupItem> found = switch (purpose) {
            case LABOR_RATE -> {
                site.require(PermissionAction.MANAGE_PROJECT);
                yield catalog.lookup(site.organizationId(), List.of(ResourceType.LABOR), false, query, page, size);
            }
            case EXPENSE -> {
                requireExpenseCreator(site);
                yield catalog.lookup(site.organizationId(),
                        List.of(ResourceType.MATERIAL, ResourceType.LABOR, ResourceType.EQUIPMENT), true, query,
                        page, size);
            }
        };
        return new ItemPage(found.content().stream()
                .map(r -> new Item(r.id(), r.code(), r.name(), r.type(), r.unitCode())).toList(),
                found.page(), found.size(), found.totalElements(), found.totalPages());
    }

    /** Budget roles record expenses on any task; anyone else only on a task they lead (ADR 0005). */
    private void requireExpenseCreator(SiteScope site) {
        if (site.can(PermissionAction.MANAGE_BUDGET)
                || activities.isLeadOnSite(site.siteId(), site.membershipId())) {
            return;
        }
        throw new AccessDeniedException("Permission required");
    }
}

package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.repository.CatalogRepository;
import org.andre.construx.modules.pricing.repository.FamilyRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.modules.pricing.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Catalog summary and its Units and Families (docs/pricing.md §2.1, §2.2). Read-only in this slice. */
@Service
public class CatalogService {
    private final CatalogRepository catalogs;
    private final ResourceRepository resources;
    private final UnitRepository units;
    private final FamilyRepository families;
    private final SupplierRepository suppliers;

    public CatalogService(CatalogRepository catalogs, ResourceRepository resources, UnitRepository units,
                          FamilyRepository families, SupplierRepository suppliers) {
        this.catalogs = catalogs;
        this.resources = resources;
        this.units = units;
        this.families = families;
        this.suppliers = suppliers;
    }

    /** Counts leave archived rows out. {@code fasar} is NONE until the FASAR slice exists. */
    public record CatalogSummary(boolean exists, String currency, Map<String, Long> counts, long missingFromImport,
                                 Object lastImport, FasarSummary fasar, Object activeImport) {}

    public record FasarSummary(String status, Integer year, String uma, String minimumWage, Instant updatedAt) {}

    public record UnitData(UUID id, String code, String description, CatalogSource source, Instant archivedAt,
                           long resourceCount) {}

    public record FamilyData(UUID id, String code, String name, CatalogSource source, Instant archivedAt,
                             long resourceCount) {}

    @Transactional(readOnly = true)
    public CatalogSummary summary(OrgScope org) {
        org.require(PermissionAction.VIEW_PRICES);
        UUID organizationId = org.organizationId();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ResourceType type : ResourceType.values()) {
            counts.put(type.name(), 0L);
        }
        var catalog = catalogs.findByOrganizationId(organizationId);
        if (catalog.isEmpty()) {
            counts.put("suppliers", 0L);
            counts.put("units", 0L);
            counts.put("families", 0L);
            return new CatalogSummary(false, "MXN", counts, 0, null, null, null);
        }
        for (Object[] row : resources.countByType(organizationId)) {
            counts.put(((ResourceType) row[0]).name(), (Long) row[1]);
        }
        counts.put("suppliers", suppliers.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        counts.put("units", units.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        counts.put("families", families.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        return new CatalogSummary(true, catalog.get().getCurrency(), counts,
                resources.countMissingFromImport(organizationId), null,
                new FasarSummary("NONE", null, null, null, null), null);
    }

    @Transactional(readOnly = true)
    public List<UnitData> units(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = counts(resources.countByUnit(org.organizationId()));
        return units.list(org.organizationId(), includeArchived).stream()
                .map(u -> new UnitData(u.getId(), u.getCode(), u.getDescription(), u.getSource(), u.getArchivedAt(),
                        used.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FamilyData> families(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = counts(resources.countByFamily(org.organizationId()));
        return families.list(org.organizationId(), includeArchived).stream()
                .map(f -> new FamilyData(f.getId(), f.getCode(), f.getName(), f.getSource(), f.getArchivedAt(),
                        used.getOrDefault(f.getId(), 0L)))
                .toList();
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }
}

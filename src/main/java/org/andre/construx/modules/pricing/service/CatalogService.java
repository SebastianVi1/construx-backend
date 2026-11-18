package org.andre.construx.modules.pricing.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.Catalog;
import org.andre.construx.modules.pricing.model.CatalogEntrySource;
import org.andre.construx.modules.pricing.model.Family;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.Unit;
import org.andre.construx.modules.pricing.repository.CatalogRepository;
import org.andre.construx.modules.pricing.repository.CountById;
import org.andre.construx.modules.pricing.repository.FamilyRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.modules.pricing.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The organization's Catalog as a whole: its summary, Units and Families (docs/pricing.md §2.1, §2.2). */
@Service
public class CatalogService {
    private final CatalogRepository catalogs;
    private final UnitRepository units;
    private final FamilyRepository families;
    private final ResourceRepository resources;
    private final SupplierRepository suppliers;

    public CatalogService(CatalogRepository catalogs, UnitRepository units, FamilyRepository families,
                          ResourceRepository resources, SupplierRepository suppliers) {
        this.catalogs = catalogs;
        this.units = units;
        this.families = families;
        this.resources = resources;
        this.suppliers = suppliers;
    }

    /**
     * Archived rows are left out of {@code counts} and of {@code missingFromImport}, as in the default lists.
     * {@code lastImport} and {@code activeImport} stay null and {@code fasar} NONE until the import and FASAR
     * slices exist.
     */
    public record CatalogSummary(boolean exists, String currency, Counts counts, long missingFromImport,
                                 ImportRef lastImport, FasarSummary fasar, ImportRef activeImport) {}

    /** Resources per type plus suppliers, units and families, under the JSON names of docs/pricing.md §2.1. */
    public record Counts(@JsonProperty("MATERIAL") long material, @JsonProperty("LABOR") long labor,
                         @JsonProperty("EQUIPMENT") long equipment, @JsonProperty("WORK_ITEM") long workItem,
                         long suppliers, long units, long families) {
        static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0, 0);
    }

    public record ImportRef(UUID id, String status, Instant finishedAt, String fileName) {}

    public record FasarSummary(String status, Integer year, String uma, String minimumWage, Instant updatedAt) {
        static final FasarSummary NONE = new FasarSummary("NONE", null, null, null, null);
    }

    public record UnitData(UUID id, String code, String description, CatalogEntrySource source, Instant archivedAt,
                           long resourceCount) {}

    public record FamilyData(UUID id, String code, String name, CatalogEntrySource source, Instant archivedAt,
                             long resourceCount) {}

    /** A missing Catalog reads as empty, never 404. */
    @Transactional(readOnly = true)
    public CatalogSummary summary(OrgScope org) {
        org.require(PermissionAction.VIEW_PRICES);
        UUID organizationId = org.organizationId();
        return catalogs.findByOrganizationId(organizationId).map(catalog -> {
            Map<ResourceType, Long> byType = new EnumMap<>(ResourceType.class);
            resources.countActiveByType(organizationId).forEach(c -> byType.put(c.getType(), c.getTotal()));
            Counts counts = new Counts(byType.getOrDefault(ResourceType.MATERIAL, 0L),
                    byType.getOrDefault(ResourceType.LABOR, 0L), byType.getOrDefault(ResourceType.EQUIPMENT, 0L),
                    byType.getOrDefault(ResourceType.WORK_ITEM, 0L),
                    suppliers.countByOrganizationIdAndArchivedAtIsNull(organizationId),
                    units.countByOrganizationIdAndArchivedAtIsNull(organizationId),
                    families.countByOrganizationIdAndArchivedAtIsNull(organizationId));
            return new CatalogSummary(true, catalog.getCurrency(), counts,
                    resources.countByOrganizationIdAndMissingFromImportAtIsNotNullAndArchivedAtIsNull(organizationId),
                    null, FasarSummary.NONE, null);
        }).orElseGet(() -> new CatalogSummary(false, null, Counts.NONE, 0, null, FasarSummary.NONE, null));
    }

    /**
     * Creates the organization's Catalog on its first write (docs/pricing.md §1.1); a no-op when it exists. Runs in
     * the caller's transaction, after the caller's permission check. Two first writes racing each other end with
     * the second one failing on the unique organization_id (409 DATA_CONFLICT), which is acceptable for v1.
     */
    @Transactional
    public void ensure(Organization organization) {
        if (catalogs.findByOrganizationId(organization.getId()).isEmpty()) {
            catalogs.saveAndFlush(new Catalog(organization, Instant.now()));
        }
    }

    /** Ordered by code; {@code resourceCount} includes archived Resources. */
    @Transactional(readOnly = true)
    public List<UnitData> units(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = totals(resources.countByUnit(org.organizationId()));
        return units.list(org.organizationId(), includeArchived).stream()
                .map(u -> toData(u, used.getOrDefault(u.getId(), 0L))).toList();
    }

    /** Ordered by code; {@code resourceCount} includes archived Resources. */
    @Transactional(readOnly = true)
    public List<FamilyData> families(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = totals(resources.countByFamily(org.organizationId()));
        return families.list(org.organizationId(), includeArchived).stream()
                .map(f -> toData(f, used.getOrDefault(f.getId(), 0L))).toList();
    }

    private static Map<UUID, Long> totals(List<CountById> counts) {
        return counts.stream().collect(Collectors.toMap(CountById::getId, CountById::getTotal));
    }

    static UnitData toData(Unit unit, long resourceCount) {
        return new UnitData(unit.getId(), unit.getCode(), unit.getDescription(), unit.getSource(),
                unit.getArchivedAt(), resourceCount);
    }

    static FamilyData toData(Family family, long resourceCount) {
        return new FamilyData(family.getId(), family.getCode(), family.getName(), family.getSource(),
                family.getArchivedAt(), resourceCount);
    }
}

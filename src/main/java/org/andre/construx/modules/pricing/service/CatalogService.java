package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.Catalog;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.Family;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.Unit;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.repository.CatalogRepository;
import org.andre.construx.modules.pricing.repository.FamilyRepository;
import org.andre.construx.modules.pricing.repository.FasarParameterSetRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.modules.pricing.repository.UnitRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Catalog summary and its Units and Families (docs/pricing.md §2.1, §2.2). */
@Service
public class CatalogService {
    private final CatalogRepository catalogs;
    private final ResourceRepository resources;
    private final UnitRepository units;
    private final FamilyRepository families;
    private final SupplierRepository suppliers;
    private final FasarParameterSetRepository fasarSets;
    private final CatalogImportService imports;

    public CatalogService(CatalogRepository catalogs, ResourceRepository resources, UnitRepository units,
                          FamilyRepository families, SupplierRepository suppliers,
                          FasarParameterSetRepository fasarSets, CatalogImportService imports) {
        this.imports = imports;
        this.fasarSets = fasarSets;
        this.catalogs = catalogs;
        this.resources = resources;
        this.units = units;
        this.families = families;
        this.suppliers = suppliers;
    }

    /** Counts leave archived rows out. */
    public record CatalogSummary(boolean exists, String currency, Map<String, Long> counts, long missingFromImport,
                                 CatalogImportService.LastImport lastImport, FasarSummary fasar,
                                 CatalogImportService.ImportData activeImport) {}

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
            return new CatalogSummary(false, "MXN", counts, 0, null, null, imports.activeImport(org));
        }
        for (Object[] row : resources.countByType(organizationId)) {
            counts.put(((ResourceType) row[0]).name(), (Long) row[1]);
        }
        counts.put("suppliers", suppliers.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        counts.put("units", units.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        counts.put("families", families.countByOrganizationIdAndArchivedAtIsNull(organizationId));
        return new CatalogSummary(true, catalog.get().getCurrency(), counts,
                resources.countMissingFromImport(organizationId),
                imports.lastImport(organizationId, catalog.get().getLastImportId()), fasar(organizationId),
                imports.activeImport(org));
    }

    /** NONE without a set; PARTIAL when it cannot compute FSRs (docs/pricing.md §5.3); COMPLETE otherwise. */
    private FasarSummary fasar(UUID organizationId) {
        return fasarSets.findByOrganizationId(organizationId)
                .map(set -> new FasarSummary(set.status().name(),
                        set.getYear() == null ? null : set.getYear().intValue(),
                        set.getUma() == null ? null : set.getUma().toPlainString(),
                        set.getMinimumWage() == null ? null : set.getMinimumWage().toPlainString(),
                        set.getUpdatedAt()))
                .orElseGet(() -> new FasarSummary("NONE", null, null, null, null));
    }

    @Transactional(readOnly = true)
    public List<UnitData> units(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = counts(resources.countByUnit(org.organizationId()));
        return units.list(org.organizationId(), includeArchived).stream()
                .map(u -> unitData(u, used.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    @Transactional
    public UnitData createUnit(OrgScope org, String code, String description) {
        org.require(PermissionAction.MANAGE_PRICES);
        Unit unit = new Unit(org.organizationId(), code, description);
        if (units.existsByOrganizationIdAndCode(org.organizationId(), unit.getCode())) {
            throw new PricingConflictException("DUPLICATE_UNIT", "A unit with this code already exists");
        }
        ensureCatalog(org.organizationId());
        return unitData(units.saveAndFlush(unit), 0);
    }

    /** Null keeps the description; an empty string clears it. */
    @Transactional
    public UnitData updateUnit(OrgScope org, UUID unitId, String description) {
        Unit unit = loadUnit(org.organizationId(), unitId);
        org.require(PermissionAction.MANAGE_PRICES);
        if (description != null) {
            unit.describe(description);
        }
        return unitData(unit, resources.countByOrganizationIdAndUnitId(org.organizationId(), unitId));
    }

    @Transactional
    public UnitData archiveUnit(OrgScope org, UUID unitId) {
        Unit unit = loadUnit(org.organizationId(), unitId);
        org.require(PermissionAction.MANAGE_PRICES);
        unit.archive(now());
        return unitData(unit, resources.countByOrganizationIdAndUnitId(org.organizationId(), unitId));
    }

    @Transactional
    public FamilyData createFamily(OrgScope org, String code, String name) {
        org.require(PermissionAction.MANAGE_PRICES);
        Family family = new Family(org.organizationId(), code, name);
        if (families.existsByOrganizationIdAndCode(org.organizationId(), family.getCode())) {
            throw new PricingConflictException("DUPLICATE_FAMILY", "A family with this code already exists");
        }
        ensureCatalog(org.organizationId());
        return familyData(families.saveAndFlush(family), 0);
    }

    /** Null keeps the name. */
    @Transactional
    public FamilyData updateFamily(OrgScope org, UUID familyId, String name) {
        Family family = loadFamily(org.organizationId(), familyId);
        org.require(PermissionAction.MANAGE_PRICES);
        if (name != null) {
            family.rename(name);
        }
        return familyData(family, resources.countByOrganizationIdAndFamilyId(org.organizationId(), familyId));
    }

    @Transactional
    public FamilyData archiveFamily(OrgScope org, UUID familyId) {
        Family family = loadFamily(org.organizationId(), familyId);
        org.require(PermissionAction.MANAGE_PRICES);
        family.archive(now());
        return familyData(family, resources.countByOrganizationIdAndFamilyId(org.organizationId(), familyId));
    }

    /** Resolves a Unit inside the tenant. */
    public Unit loadUnit(UUID organizationId, UUID unitId) {
        return units.findByIdAndOrganizationId(unitId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unit not found"));
    }

    /** Resolves a Family inside the tenant. */
    public Family loadFamily(UUID organizationId, UUID familyId) {
        return families.findByIdAndOrganizationId(familyId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Family not found"));
    }

    /** The first catalog write of an organization creates its Catalog (docs/pricing.md §1.1). */
    @Transactional
    public void ensureCatalog(UUID organizationId) {
        if (catalogs.findByOrganizationId(organizationId).isEmpty()) {
            catalogs.saveAndFlush(Catalog.open(organizationId, now()));
        }
    }

    /** Microsecond precision, as stored, so a repeated archive answers the same time. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static UnitData unitData(Unit u, long resourceCount) {
        return new UnitData(u.getId(), u.getCode(), u.getDescription(), u.getSource(), u.getArchivedAt(),
                resourceCount);
    }

    private static FamilyData familyData(Family f, long resourceCount) {
        return new FamilyData(f.getId(), f.getCode(), f.getName(), f.getSource(), f.getArchivedAt(), resourceCount);
    }

    @Transactional(readOnly = true)
    public List<FamilyData> families(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = counts(resources.countByFamily(org.organizationId()));
        return families.list(org.organizationId(), includeArchived).stream()
                .map(f -> familyData(f, used.getOrDefault(f.getId(), 0L)))
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

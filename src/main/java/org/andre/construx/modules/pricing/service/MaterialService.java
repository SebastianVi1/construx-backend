package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.pricing.model.Material;
import org.andre.construx.modules.pricing.model.MaterialCategory;
import org.andre.construx.modules.pricing.model.MaterialUnit;
import org.andre.construx.modules.pricing.model.PriceObservation;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.PricingText;
import org.andre.construx.modules.pricing.repository.MaterialRepository;
import org.andre.construx.modules.pricing.repository.PriceObservationRepository;
import org.andre.construx.modules.pricing.service.PriceObservationService.LatestPrice;
import org.andre.construx.modules.pricing.service.PriceObservationService.PriceData;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The organization's material catalog and the prices recorded for it. */
@Service
public class MaterialService {
    private final MaterialRepository materials;
    private final PriceObservationRepository observations;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public MaterialService(MaterialRepository materials, PriceObservationRepository observations,
                           MembershipGuard guard, DomainAuthorization authorization) {
        this.materials = materials;
        this.observations = observations;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** {@code latestPrices}: newest observation per non-archived supplier, cheapest first. */
    public record MaterialData(UUID id, String name, MaterialUnit unit, MaterialCategory category, String brand,
                               Instant archivedAt, List<LatestPrice> latestPrices, UUID cheapestSupplierId) {}

    public record MaterialDetail(UUID id, String name, MaterialUnit unit, MaterialCategory category, String brand,
                                 Instant archivedAt, List<LatestPrice> latestPrices, UUID cheapestSupplierId,
                                 List<PriceData> history) {}

    @Transactional
    public MaterialData create(UUID userId, UUID organizationId, String name, MaterialUnit unit,
                               MaterialCategory category, String brand) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        Material material = new Material(member.getOrganization(), name, unit, category, brand);
        rejectDuplicate(organizationId, null, material.getNormalizedKey());
        return summarize(List.of(materials.saveAndFlush(material))).get(0);
    }

    @Transactional(readOnly = true)
    public List<MaterialData> list(UUID userId, UUID organizationId, String query, MaterialCategory category,
                                   boolean includeArchived) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_PRICES);
        return summarize(materials.search(organizationId, includeArchived, category, pattern(query)));
    }

    @Transactional(readOnly = true)
    public MaterialDetail get(UUID userId, UUID organizationId, UUID materialId) {
        guard.requireActive(userId, organizationId);
        Material material = load(organizationId, materialId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_PRICES);
        List<PriceObservation> history = observations.findNewestFirst(List.of(materialId));
        List<LatestPrice> latest = latest(history);
        return new MaterialDetail(material.getId(), material.getName(), material.getUnit(), material.getCategory(),
                material.getBrand(), material.getArchivedAt(), latest, cheapest(latest),
                history.stream().map(PriceObservationService::toData).toList());
    }

    /** Null arguments keep the stored value; an empty brand clears it. */
    @Transactional
    public MaterialData update(UUID userId, UUID organizationId, UUID materialId, String name, MaterialUnit unit,
                               MaterialCategory category, String brand) {
        guard.requireActive(userId, organizationId);
        Material material = load(organizationId, materialId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        String newName = name != null ? name : material.getName();
        MaterialUnit newUnit = unit != null ? unit : material.getUnit();
        MaterialCategory newCategory = category != null ? category : material.getCategory();
        String newBrand = brand != null ? brand : material.getBrand();
        rejectDuplicate(organizationId, materialId, Material.keyOf(newName, newUnit, newBrand));
        material.describe(newName, newUnit, newCategory, newBrand);
        return summarize(List.of(materials.saveAndFlush(material))).get(0);
    }

    /** Materials are never deleted, so their price history stays readable. */
    @Transactional
    public MaterialData archive(UUID userId, UUID organizationId, UUID materialId) {
        guard.requireActive(userId, organizationId);
        Material material = load(organizationId, materialId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        material.archive(Instant.now());
        return summarize(List.of(material)).get(0);
    }

    /** Resolves a material inside the tenant. */
    public Material load(UUID organizationId, UUID materialId) {
        return materials.findByIdAndOrganizationId(materialId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Material not found"));
    }

    private void rejectDuplicate(UUID organizationId, UUID selfId, String key) {
        materials.findByOrganizationIdAndNormalizedKey(organizationId, key)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new PricingConflictException("DUPLICATE_MATERIAL",
                            "A material with this name, unit and brand already exists");
                });
    }

    private List<MaterialData> summarize(List<Material> list) {
        Map<UUID, List<PriceObservation>> byMaterial = new HashMap<>();
        if (!list.isEmpty()) {
            for (PriceObservation o : observations.findNewestFirst(list.stream().map(Material::getId).toList())) {
                byMaterial.computeIfAbsent(o.getMaterial().getId(), id -> new ArrayList<>()).add(o);
            }
        }
        return list.stream().map(m -> {
            List<LatestPrice> latest = latest(byMaterial.getOrDefault(m.getId(), List.of()));
            return new MaterialData(m.getId(), m.getName(), m.getUnit(), m.getCategory(), m.getBrand(),
                    m.getArchivedAt(), latest, cheapest(latest));
        }).toList();
    }

    /** {@code newestFirst} must be ordered by observation day then creation, newest first. */
    private static List<LatestPrice> latest(List<PriceObservation> newestFirst) {
        Map<UUID, PriceObservation> perSupplier = new LinkedHashMap<>();
        for (PriceObservation o : newestFirst) {
            if (!o.getSupplier().isArchived()) {
                perSupplier.putIfAbsent(o.getSupplier().getId(), o);
            }
        }
        BigDecimal min = perSupplier.values().stream().map(PriceObservation::getPrice)
                .min(Comparator.naturalOrder()).orElse(null);
        return perSupplier.values().stream()
                .sorted(Comparator.comparing(PriceObservation::getPrice)
                        .thenComparing(o -> PricingText.fold(o.getSupplier().getName())))
                .map(o -> PriceObservationService.toLatest(o, o.getPrice().compareTo(min) == 0))
                .toList();
    }

    private static UUID cheapest(List<LatestPrice> latest) {
        return latest.isEmpty() ? null : latest.get(0).supplierId();
    }

    /** Lower-case LIKE pattern for a contains search, or null when the query is blank. */
    private static String pattern(String query) {
        String folded = PricingText.fold(query);
        if (folded.isEmpty()) {
            return null;
        }
        return "%" + folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

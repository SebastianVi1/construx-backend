package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.ResourceDirectory;
import org.andre.construx.modules.pricing.service.ResourceDirectory.CatalogResource;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EquipmentService {
    /** Hours in the working day used to turn an hourly Equipment rate into a daily cost (docs/pricing.md §6.6). */
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(8);

    private final EquipmentRepository equipmentRepo;
    private final ResourceDirectory catalog;

    public EquipmentService(EquipmentRepository equipmentRepo, ResourceDirectory catalog) {
        this.equipmentRepo = equipmentRepo;
        this.catalog = catalog;
    }

    /** {@code equipmentRate} is null without a linked Equipment rate. */
    public record EquipmentData(UUID id, UUID organizationId, String type, String identifier,
                                Long dailyCostCents, boolean active, Instant createdAt, EquipmentRateRef equipmentRate) {}

    /** {@code hourlyCost} is the rate's current cost, a two-decimal string, null while it has none. */
    public record EquipmentRateRef(UUID id, String code, String name, String hourlyCost) {}

    /**
     * How a request touches the Equipment rate link: {@link #KEEP}, {@link #CLEAR} or {@link #set(UUID)}.
     * Setting it without a daily cost prefills the daily cost from the rate.
     */
    public record RateLink(boolean sent, UUID resourceId) {
        public static final RateLink KEEP = new RateLink(false, null);
        public static final RateLink CLEAR = new RateLink(true, null);

        public static RateLink set(UUID resourceId) {
            return resourceId == null ? KEEP : new RateLink(true, resourceId);
        }
    }

    @Transactional
    public EquipmentData createEquipment(OrgScope org, String type,
                                         String identifier, Long dailyCostCents, RateLink rate) {
        org.require(PermissionAction.MANAGE_PLAN);
        CatalogResource linked = rate.resourceId() == null ? null : equipmentRate(org, rate.resourceId());
        Equipment equipment = new Equipment(org.membership().getOrganization(), type, identifier,
                dailyCostCents != null ? dailyCostCents : dailyCostOf(linked));
        equipment.linkRate(linked == null ? null : linked.id());
        return toData(equipmentRepo.save(equipment), linked);
    }

    /** Sending the linked rate again without a daily cost refreshes the daily cost from it ("update from rate"). */
    @Transactional
    public EquipmentData updateEquipment(OrgScope org, UUID equipmentId, String type, String identifier,
                                         Long dailyCostCents, Boolean active, RateLink rate) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        CatalogResource linked = rate.resourceId() == null ? null : equipmentRate(org, rate.resourceId());
        Long daily = dailyCostCents == null && linked != null ? dailyCostOf(linked) : dailyCostCents;
        equipment.update(type, identifier, daily, active);
        if (rate.sent()) {
            equipment.linkRate(linked == null ? null : linked.id());
        }
        return toData(equipment);
    }

    @Transactional
    public EquipmentData deactivateEquipment(OrgScope org, UUID equipmentId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        equipment.deactivate();
        return toData(equipment);
    }

    @Transactional(readOnly = true)
    public List<EquipmentData> listEquipment(OrgScope org, Boolean activeOnly) {
        org.require(PermissionAction.VIEW_PLAN);
        List<Equipment> result = (activeOnly != null && activeOnly)
                ? equipmentRepo.findByOrganizationIdAndActiveOrderByTypeAscIdentifierAsc(org.organizationId(), true)
                : equipmentRepo.findByOrganizationIdOrderByTypeAscIdentifierAsc(org.organizationId());
        Set<UUID> rateIds = result.stream().map(Equipment::getEquipmentRateResourceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, CatalogResource> rates = catalog.byIds(org.organizationId(), rateIds);
        return result.stream().map(e -> toData(e, e.getEquipmentRateResourceId() == null ? null
                : rates.get(e.getEquipmentRateResourceId()))).toList();
    }

    @Transactional
    public void deleteEquipment(OrgScope org, UUID equipmentId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        equipmentRepo.delete(equipment);
    }

    private Equipment loadEquipment(UUID equipmentId, UUID organizationId) {
        return equipmentRepo.findByIdAndOrganizationId(equipmentId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipment not found"));
    }

    /** An EQUIPMENT Resource of the organization: another tenant's or a missing id is 404, another type 400. */
    private CatalogResource equipmentRate(OrgScope org, UUID resourceId) {
        CatalogResource resource = catalog.get(org.organizationId(), resourceId);
        if (resource.type() != ResourceType.EQUIPMENT) {
            throw new RuleViolationException("NOT_AN_EQUIPMENT_RATE", "Only an equipment rate can be linked");
        }
        return resource;
    }

    /** {@code round(hourlyCost × 8 × 100)}, null while the rate has no cost. */
    private static Long dailyCostOf(CatalogResource rate) {
        if (rate == null || rate.currentCost() == null) {
            return null;
        }
        return rate.currentCost().multiply(HOURS_PER_DAY).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private EquipmentData toData(Equipment e) {
        return toData(e, e.getEquipmentRateResourceId() == null ? null
                : catalog.byIds(e.getOrganization().getId(), Set.of(e.getEquipmentRateResourceId()))
                        .get(e.getEquipmentRateResourceId()));
    }

    private static EquipmentData toData(Equipment e, CatalogResource rate) {
        return new EquipmentData(e.getId(), e.getOrganization().getId(), e.getType(),
                e.getIdentifier(), e.getDailyCostCents(), e.isActive(), e.getCreatedAt(),
                rate == null ? null : new EquipmentRateRef(rate.id(), rate.code(), rate.name(),
                        rate.currentCost() == null ? null : rate.currentCost().setScale(2, RoundingMode.HALF_UP)
                                .toPlainString()));
    }
}

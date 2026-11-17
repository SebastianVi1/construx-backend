package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.DerivedCost;
import org.andre.construx.modules.pricing.model.EquipmentLine;
import org.andre.construx.modules.pricing.model.EquipmentRateDetail;
import org.andre.construx.modules.pricing.model.FsrSource;
import org.andre.construx.modules.pricing.model.LaborCategoryDetail;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.UnitPriceAnalysisLine;
import org.andre.construx.modules.pricing.repository.EquipmentRateDetailRepository;
import org.andre.construx.modules.pricing.repository.EquipmentStandbyPercentageRepository;
import org.andre.construx.modules.pricing.repository.LaborCategoryDetailRepository;
import org.andre.construx.modules.pricing.repository.UnitPriceAnalysisLineRepository;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceRef;
import org.andre.construx.modules.pricing.service.ResourceService.UnitRef;
import org.andre.construx.modules.pricing.service.ResourceService.UsedIn;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The composition parts of a Resource's detail (docs/pricing.md §2.4): its Unit price analysis, Labor category
 * wage, Equipment rate machinery data and where it is used; and the catalog's equipment standby percentages.
 * Money is a string with two decimals; quantities and factors are plain decimals without trailing zeros.
 */
@Service
public class ResourceCompositionService {
    static final int USED_IN_SAMPLE = 10;

    private final UnitPriceAnalysisLineRepository analysisLines;
    private final LaborCategoryDetailRepository laborDetails;
    private final EquipmentRateDetailRepository equipmentDetails;
    private final EquipmentStandbyPercentageRepository standbyPercentages;

    public ResourceCompositionService(UnitPriceAnalysisLineRepository analysisLines,
                                      LaborCategoryDetailRepository laborDetails,
                                      EquipmentRateDetailRepository equipmentDetails,
                                      EquipmentStandbyPercentageRepository standbyPercentages) {
        this.analysisLines = analysisLines;
        this.laborDetails = laborDetails;
        this.equipmentDetails = equipmentDetails;
        this.standbyPercentages = standbyPercentages;
    }

    public record AnalysisComponent(UUID id, String code, String name, ResourceType type, String unitCode,
                                    boolean isPercentage, String currentCost) {}

    public record AnalysisLine(int lineNo, AnalysisComponent component, String operator, String quantity,
                               String expression, String amount) {}

    /** {@code incomplete} when some Resource below has no cost yet; {@code mismatch} per §3.3. */
    public record Analysis(String derivedCost, String importedCost, boolean mismatch, boolean incomplete,
                           String laborSubtotal, List<AnalysisLine> lines) {}

    public record ImportedWage(String fsr, String realWage, String fsbc, String sbc) {}

    /** {@code realWage} is the Resource's current cost. */
    public record LaborDetail(String nominalWage, String fsr, FsrSource fsrSource, String fsbc, String sbc,
                              String perDiem, String mealsLodging, String funeralExpenses, String otherBenefits,
                              Short fasarType, ImportedWage imported, boolean fsrMismatch, String realWage) {}

    public record EquipmentComponent(UUID id, String code, String name, ResourceType type, UnitRef unit) {}

    public record EquipmentLineData(int lineNo, EquipmentComponent component, String operator, String quantity) {}

    public record EquipmentDetail(String powerUnit, String fixedChargeCode, String acquisitionValueCode,
                                  String standbyCode, String reserveCode, String tiresCode, String specialPartsCode,
                                  String fuelCode, String lubricantCode, String otherEnergyCode,
                                  String tiresLifeHours, String specialPartsLifeHours, String economicLifeHours,
                                  String hoursPerYear, String oilChangeHours, Short calculationType, String power,
                                  String operationFactor, Short fuelType, String fuelFactor,
                                  String otherEnergyFactor, String lubricantFactor, String crankcaseCapacity,
                                  String salvagePct, String insurancePct, String maintenanceFactor,
                                  String interestRate, String brand, String model, String serialNumber,
                                  String capacity, String location, String usefulLife, String owner,
                                  String availability, String commercialValue, String fuelQtyPerHour,
                                  String lubricantQtyPerHour, List<EquipmentLineData> operationLines,
                                  List<EquipmentLineData> consumptionLines) {}

    public record StandbyPercentage(String charge, String standbyPct, String reservePct) {}

    @Transactional(readOnly = true)
    public List<StandbyPercentage> standbyPercentages(OrgScope org) {
        org.require(PermissionAction.VIEW_PRICES);
        return standbyPercentages.findByOrganizationIdOrderByCharge(org.organizationId()).stream()
                .map(p -> new StandbyPercentage(p.getCharge(), decimal(p.getStandbyPct()),
                        decimal(p.getReservePct())))
                .toList();
    }

    /**
     * The analysis of a composite, derived live from its subtree so it always agrees with its components;
     * null for a simple Resource.
     */
    Analysis analysis(Resource root) {
        if (!root.isComposite()) {
            return null;
        }
        UUID organizationId = root.getOrganizationId();
        Map<UUID, Resource> components = new HashMap<>();
        components.put(root.getId(), root);
        List<UnitPriceAnalysisLine> subtree = new ArrayList<>();
        Set<UUID> expanded = new HashSet<>(Set.of(root.getId()));
        Set<UUID> frontier = Set.of(root.getId());
        while (!frontier.isEmpty()) {
            List<UnitPriceAnalysisLine> found = analysisLines.findByParents(organizationId, frontier);
            subtree.addAll(found);
            Set<UUID> next = new HashSet<>();
            for (UnitPriceAnalysisLine line : found) {
                Resource component = line.getComponent();
                components.putIfAbsent(component.getId(), component);
                if (component.isComposite() && expanded.add(component.getId())) {
                    next.add(component.getId());
                }
            }
            frontier = next;
        }
        DerivedCost.Result result = DerivedCost.derive(
                components.values().stream().map(Resource::toComponent).toList(),
                subtree.stream().map(UnitPriceAnalysisLine::toLine).toList()).get(root.getId());
        List<AnalysisLine> lines = subtree.stream()
                .filter(l -> l.getParentResourceId().equals(root.getId()))
                .sorted(Comparator.comparingInt(UnitPriceAnalysisLine::getLineNo))
                .map(l -> {
                    Resource c = l.getComponent();
                    return new AnalysisLine(l.getLineNo(), new AnalysisComponent(c.getId(), c.getCode(),
                            c.getName(), c.getType(), c.getUnit().getCode(), c.isPercentage(),
                            ResourceService.money(c.getCurrentCost())), String.valueOf(l.getOperator()),
                            decimal(l.getQuantity()), l.getExpression(),
                            ResourceService.money(result.lineAmounts().get(l.getId())));
                })
                .toList();
        return new Analysis(ResourceService.money(result.cost()), ResourceService.money(root.getImportedCost()),
                DerivedCost.mismatch(result.cost(), root.getImportedCost()), result.incomplete(),
                ResourceService.money(result.laborSubtotal()), lines);
    }

    /** The wage of a simple Labor category; null for anything else or when it has no detail row. */
    LaborDetail laborDetail(Resource r) {
        if (r.getType() != ResourceType.LABOR || r.isComposite()) {
            return null;
        }
        return laborDetails.findByResourceIdAndOrganizationId(r.getId(), r.getOrganizationId())
                .map(d -> toLaborDetail(d, r))
                .orElse(null);
    }

    private static LaborDetail toLaborDetail(LaborCategoryDetail d, Resource r) {
        return new LaborDetail(ResourceService.money(d.getNominalWage()), decimal(d.getFsr()), d.getFsrSource(),
                decimal(d.getFsbc()), ResourceService.money(d.getSbc()), ResourceService.money(d.getPerDiem()),
                ResourceService.money(d.getMealsLodging()), ResourceService.money(d.getFuneralExpenses()),
                ResourceService.money(d.getOtherBenefits()), d.getFasarType(),
                new ImportedWage(decimal(d.getImportedFsr()), ResourceService.money(d.getImportedRealWage()),
                        decimal(d.getImportedFsbc()), ResourceService.money(d.getImportedSbc())),
                d.isFsrMismatch(), ResourceService.money(r.getCurrentCost()));
    }

    /** The machinery data of an Equipment rate; null for anything else or when it has no detail row. */
    EquipmentDetail equipmentDetail(Resource r) {
        if (r.getType() != ResourceType.EQUIPMENT) {
            return null;
        }
        UUID organizationId = r.getOrganizationId();
        return equipmentDetails.findByResourceIdAndOrganizationId(r.getId(), organizationId)
                .map(d -> toEquipmentDetail(d,
                        equipmentDetails.operationLines(organizationId, r.getId()).stream()
                                .map(ResourceCompositionService::toLine).toList(),
                        equipmentDetails.consumptionLines(organizationId, r.getId()).stream()
                                .map(ResourceCompositionService::toLine).toList()))
                .orElse(null);
    }

    private static EquipmentLineData toLine(EquipmentLine l) {
        Resource c = l.getComponent();
        return new EquipmentLineData(l.getLineNo(), new EquipmentComponent(c.getId(), c.getCode(), c.getName(),
                c.getType(), new UnitRef(c.getUnit().getId(), c.getUnit().getCode())),
                String.valueOf(l.getOperator()), decimal(l.getQuantity()));
    }

    private static EquipmentDetail toEquipmentDetail(EquipmentRateDetail d, List<EquipmentLineData> operation,
                                                     List<EquipmentLineData> consumption) {
        return new EquipmentDetail(d.getPowerUnit(), d.getFixedChargeCode(), d.getAcquisitionValueCode(),
                d.getStandbyCode(), d.getReserveCode(), d.getTiresCode(), d.getSpecialPartsCode(), d.getFuelCode(),
                d.getLubricantCode(), d.getOtherEnergyCode(), decimal(d.getTiresLifeHours()),
                decimal(d.getSpecialPartsLifeHours()), decimal(d.getEconomicLifeHours()),
                decimal(d.getHoursPerYear()), decimal(d.getOilChangeHours()), d.getCalculationType(),
                decimal(d.getPower()), decimal(d.getOperationFactor()), d.getFuelType(), decimal(d.getFuelFactor()),
                decimal(d.getOtherEnergyFactor()), decimal(d.getLubricantFactor()),
                decimal(d.getCrankcaseCapacity()), decimal(d.getSalvagePct()), decimal(d.getInsurancePct()),
                decimal(d.getMaintenanceFactor()), decimal(d.getInterestRate()), d.getBrand(), d.getModel(),
                d.getSerialNumber(), d.getCapacity(), d.getLocation(), d.getUsefulLife(), d.getOwner(),
                d.getAvailability(), ResourceService.money(d.getCommercialValue()), decimal(d.getFuelQtyPerHour()),
                decimal(d.getLubricantQtyPerHour()), operation, consumption);
    }

    /** How many composites use the Resource and up to {@value #USED_IN_SAMPLE} of them by code. */
    UsedIn usedIn(Resource r) {
        long count = analysisLines.countParents(r.getOrganizationId(), r.getId());
        if (count == 0) {
            return new UsedIn(0, List.of());
        }
        return new UsedIn(count, analysisLines.findParents(r.getOrganizationId(), r.getId(),
                        PageRequest.of(0, USED_IN_SAMPLE)).stream()
                .map(p -> new ResourceRef(p.getId(), p.getCode(), p.getName(), p.getType()))
                .toList());
    }

    /** A quantity or factor without trailing zeros, never in exponent notation; null stays null. */
    static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}

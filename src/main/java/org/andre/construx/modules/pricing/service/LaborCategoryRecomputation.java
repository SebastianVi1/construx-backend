package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.CostSource;
import org.andre.construx.modules.pricing.model.FasarCalculator;
import org.andre.construx.modules.pricing.model.FasarCalculator.Parameters;
import org.andre.construx.modules.pricing.model.FasarParameterSet;
import org.andre.construx.modules.pricing.model.LaborCategoryDetail;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.repository.FasarParameterSetRepository;
import org.andre.construx.modules.pricing.repository.LaborCategoryDetailRepository;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The organization's complete FASAR set applied to its Labor categories (docs/pricing.md §5.5), shared by
 * {@code PUT /fasar} and the Catalog import; it needs only repositories, so the import worker can use it without
 * depending on the services that read imports. Runs inside the caller's transaction.
 */
@Component
class LaborCategoryRecomputation {
    private final FasarParameterSetRepository sets;
    private final LaborCategoryDetailRepository laborDetails;
    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository history;

    LaborCategoryRecomputation(FasarParameterSetRepository sets, LaborCategoryDetailRepository laborDetails,
                               ResourceRepository resources, ResourceCostHistoryRepository history) {
        this.sets = sets;
        this.laborDetails = laborDetails;
        this.resources = resources;
        this.history = history;
    }

    /** The calculator of the organization's complete set, empty without one (a partial set cannot compute). */
    @Transactional(readOnly = true)
    public Optional<FasarCalculator> calculator(UUID organizationId) {
        return sets.findByOrganizationId(organizationId)
                .filter(FasarParameterSet::isComplete)
                .map(FasarParameterSet::calculator);
    }

    /**
     * Replaces the organization's set with the complete one of a FASAR workbook (source {@code FASAR_FILE},
     * {@code version} + 1). The caller recomputes afterwards.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceFromFile(UUID organizationId, Integer year, BigDecimal minimumWage, Parameters parameters,
                                Instant at, UUID membershipId) {
        FasarParameterSet set = sets.findByOrganizationId(organizationId).orElse(null);
        if (set == null) {
            sets.save(FasarParameterSet.fromFile(organizationId, year, minimumWage, parameters, at, membershipId));
        } else {
            set.replaceFromFile(year, minimumWage, parameters, at, membershipId);
        }
    }

    /**
     * With a complete set, recomputes every simple Labor category of the organization from it and writes a
     * {@code FASAR} Cost history entry for every changed real wage; returns how many changed. Without one,
     * nothing. Derived costs are left to the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recomputeAll(UUID organizationId, Instant at, UUID membershipId) {
        return calculator(organizationId).map(fasar -> recompute(organizationId, fasar, at, membershipId)).orElse(0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int recompute(UUID organizationId, FasarCalculator fasar, Instant at, UUID membershipId) {
        List<LaborCategoryDetail> details = laborDetails.findByOrganizationId(organizationId);
        Map<UUID, Resource> byId = resources.findAllInOrganization(
                        details.stream().map(LaborCategoryDetail::getResourceId).toList(), organizationId).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
        int changed = 0;
        for (LaborCategoryDetail detail : details) {
            detail.compute(fasar);
            var entry = byId.get(detail.getResourceId()).recordWage(detail, at, membershipId, CostSource.FASAR);
            if (entry != null) {
                history.save(entry);
                changed++;
            }
        }
        return changed;
    }
}

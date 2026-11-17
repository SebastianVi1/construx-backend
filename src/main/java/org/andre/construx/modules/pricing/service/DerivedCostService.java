package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.DerivedCost;
import org.andre.construx.modules.pricing.model.DerivedCost.Component;
import org.andre.construx.modules.pricing.model.DerivedCost.Result;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.UnitPriceAnalysisLine;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.UnitPriceAnalysisLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recomputes the derived cost of every composite Resource of an organization (docs/pricing.md §3.2) after a
 * write that can change a cost, in the writer's transaction: two queries, the pure {@link DerivedCost} engine,
 * and only the changed costs and line amounts written back.
 */
@Service
public class DerivedCostService {
    private final ResourceRepository resources;
    private final UnitPriceAnalysisLineRepository lines;

    public DerivedCostService(ResourceRepository resources, UnitPriceAnalysisLineRepository lines) {
        this.resources = resources;
        this.lines = lines;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(UUID organizationId, Instant at) {
        resources.flush();
        List<Component> components = resources.derivationInputs(organizationId).stream()
                .map(row -> new Component((UUID) row[0], (ResourceType) row[1], (Boolean) row[2], (Boolean) row[3],
                        (BigDecimal) row[4]))
                .toList();
        if (components.stream().noneMatch(Component::composite)) {
            return;
        }
        List<UnitPriceAnalysisLine> analysisLines = lines.findByOrganizationId(organizationId);
        Map<UUID, Result> results = DerivedCost.derive(components,
                analysisLines.stream().map(UnitPriceAnalysisLine::toLine).toList());
        for (Resource composite : resources.findByOrganizationIdAndCompositeTrue(organizationId)) {
            composite.deriveCost(results.get(composite.getId()).cost(), at);
        }
        for (UnitPriceAnalysisLine line : analysisLines) {
            line.materialize(results.get(line.getParentResourceId()).lineAmounts().get(line.getId()));
        }
    }
}

package org.andre.construx.modules.pricing.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The derived cost of composite Resources from their Unit price analyses (docs/pricing.md §3), from plain
 * values so it runs without JPA. Every line amount is rounded half-up to two decimals and the cost of a
 * composite is the sum of its rounded line amounts.
 */
public final class DerivedCost {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal MISMATCH_FLOOR = new BigDecimal("0.50");
    private static final BigDecimal MISMATCH_SHARE = new BigDecimal("0.005");

    /** A Resource as the engine sees it; {@code currentCost} matters only for simple ones, null when it has none. */
    public record Component(UUID id, ResourceType type, boolean composite, boolean percentage,
                            BigDecimal currentCost) {}

    /** {@code operator} is {@code *} (consumption) or {@code /} (output). */
    public record Line(UUID id, UUID parentId, UUID componentId, int lineNo, char operator, BigDecimal quantity) {}

    /**
     * The derivation of one composite. {@code incomplete} when some Resource in its subtree has no cost yet;
     * {@code lineAmounts} by line id.
     */
    public record Result(BigDecimal cost, BigDecimal laborSubtotal, boolean incomplete,
                         Map<UUID, BigDecimal> lineAmounts) {}

    private final Map<UUID, Component> resources = new HashMap<>();
    private final Map<UUID, List<Line>> linesByParent = new HashMap<>();
    private final Map<UUID, Result> results = new HashMap<>();
    private final Set<UUID> visiting = new HashSet<>();

    private DerivedCost(Collection<Component> resources, Collection<Line> lines) {
        resources.forEach(r -> this.resources.put(r.id(), r));
        for (Line line : lines) {
            linesByParent.computeIfAbsent(line.parentId(), k -> new ArrayList<>()).add(line);
        }
    }

    /**
     * Derives every composite of {@code resources}. A component missing from {@code resources} or a cycle
     * between analyses is a broken Catalog and fails with {@link IllegalStateException}.
     */
    public static Map<UUID, Result> derive(Collection<Component> resources, Collection<Line> lines) {
        DerivedCost engine = new DerivedCost(resources, lines);
        for (Component r : resources) {
            if (r.composite()) {
                engine.derive(r.id());
            }
        }
        return engine.results;
    }

    /** {@code |derived - imported| > max(0.50, 0.5 % of imported)}; never a mismatch without an imported cost. */
    public static boolean mismatch(BigDecimal derived, BigDecimal imported) {
        if (derived == null || imported == null) {
            return false;
        }
        BigDecimal tolerance = MISMATCH_FLOOR.max(MISMATCH_SHARE.multiply(imported.abs()));
        return derived.subtract(imported).abs().compareTo(tolerance) > 0;
    }

    private Result derive(UUID parentId) {
        Result done = results.get(parentId);
        if (done != null) {
            return done;
        }
        if (!visiting.add(parentId)) {
            throw new IllegalStateException("Unit price analyses form a cycle through resource " + parentId);
        }
        List<Line> lines = new ArrayList<>(linesByParent.getOrDefault(parentId, List.of()));
        lines.sort(Comparator.comparingInt(Line::lineNo));
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        BigDecimal laborSubtotal = ZERO;
        boolean incomplete = false;
        List<Line> percentageLines = new ArrayList<>();
        for (Line line : lines) {
            Component component = component(line);
            if (component.percentage()) {
                percentageLines.add(line);
                continue;
            }
            BigDecimal cost;
            if (component.composite()) {
                Result inner = derive(component.id());
                cost = inner.cost();
                incomplete |= inner.incomplete();
            } else if (component.currentCost() == null) {
                cost = ZERO;
                incomplete = true;
            } else {
                cost = component.currentCost();
            }
            BigDecimal amount = line.operator() == '/'
                    ? cost.divide(line.quantity(), MathContext.DECIMAL128)
                    : line.quantity().multiply(cost);
            amount = round2(amount);
            amounts.put(line.id(), amount);
            if (component.type() == ResourceType.LABOR) {
                laborSubtotal = laborSubtotal.add(amount);
            }
        }
        for (Line line : percentageLines) {
            amounts.put(line.id(), round2(line.quantity().multiply(laborSubtotal)));
        }
        BigDecimal cost = amounts.values().stream().reduce(ZERO, BigDecimal::add);
        visiting.remove(parentId);
        Result result = new Result(cost, laborSubtotal, incomplete, amounts);
        results.put(parentId, result);
        return result;
    }

    private Component component(Line line) {
        Component component = resources.get(line.componentId());
        if (component == null) {
            throw new IllegalStateException("Analysis line " + line.id() + " uses an unknown resource");
        }
        return component;
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

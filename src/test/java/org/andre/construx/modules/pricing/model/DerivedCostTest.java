package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.pricing.model.DerivedCost.Component;
import org.andre.construx.modules.pricing.model.DerivedCost.Line;
import org.andre.construx.modules.pricing.model.DerivedCost.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The derived cost of composite Resources (docs/pricing.md §3), worked by hand. */
class DerivedCostTest {
    private final List<Component> resources = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();

    private UUID simple(ResourceType type, String cost) {
        UUID id = UUID.randomUUID();
        resources.add(new Component(id, type, false, false, cost == null ? null : new BigDecimal(cost)));
        return id;
    }

    private UUID percentage(String cost) {
        UUID id = UUID.randomUUID();
        resources.add(new Component(id, ResourceType.MATERIAL, false, true, cost == null ? null : new BigDecimal(cost)));
        return id;
    }

    private UUID composite(ResourceType type) {
        UUID id = UUID.randomUUID();
        resources.add(new Component(id, type, true, false, null));
        return id;
    }

    private UUID line(UUID parent, int lineNo, UUID component, char operator, String quantity) {
        UUID id = UUID.randomUUID();
        lines.add(new Line(id, parent, component, lineNo, operator, new BigDecimal(quantity)));
        return id;
    }

    private Map<UUID, Result> derive() {
        return DerivedCost.derive(resources, lines);
    }

    @Test
    void consumptionLinesMultiplyAndOutputLinesDivide() {
        UUID cement = simple(ResourceType.MATERIAL, "245.50");
        UUID gang = simple(ResourceType.LABOR, "1000.00");
        UUID wall = composite(ResourceType.WORK_ITEM);
        UUID consumption = line(wall, 1, cement, '*', "0.0300");
        UUID output = line(wall, 2, gang, '/', "12");

        Result result = derive().get(wall);

        assertEquals(new BigDecimal("7.37"), result.lineAmounts().get(consumption)); // 7.365 half-up
        assertEquals(new BigDecimal("83.33"), result.lineAmounts().get(output)); // 83.333...
        assertEquals(new BigDecimal("90.70"), result.cost());
        assertEquals(new BigDecimal("83.33"), result.laborSubtotal());
        assertFalse(result.incomplete());
    }

    @Test
    void theCostIsTheSumOfAlreadyRoundedLineAmounts() {
        UUID a = simple(ResourceType.MATERIAL, "0.01");
        UUID item = composite(ResourceType.WORK_ITEM);
        line(item, 1, a, '*', "0.5"); // 0.005 -> 0.01
        line(item, 2, a, '*', "0.5"); // 0.005 -> 0.01

        assertEquals(new BigDecimal("0.02"), derive().get(item).cost());
    }

    @Test
    void percentageLinesApplyToTheLaborSubtotalAfterEveryOtherLine() {
        UUID tools = percentage("0.00");
        UUID peon = simple(ResourceType.LABOR, "500.00");
        UUID sand = simple(ResourceType.MATERIAL, "290.00");
        UUID item = composite(ResourceType.WORK_ITEM);
        UUID toolsLine = line(item, 1, tools, '*', "0.03");
        line(item, 2, peon, '/', "8");     // 62.50
        line(item, 3, sand, '*', "0.1");   // 29.00

        Result result = derive().get(item);

        assertEquals(new BigDecimal("62.50"), result.laborSubtotal());
        assertEquals(new BigDecimal("1.88"), result.lineAmounts().get(toolsLine)); // 1.875 half-up
        assertEquals(new BigDecimal("93.38"), result.cost());
    }

    @Test
    void percentageLabourResourcesAreNotPartOfTheLaborSubtotal() {
        UUID laborPercent = UUID.randomUUID();
        resources.add(new Component(laborPercent, ResourceType.LABOR, false, true, new BigDecimal("0.00")));
        UUID peon = simple(ResourceType.LABOR, "100.00");
        UUID item = composite(ResourceType.WORK_ITEM);
        line(item, 1, peon, '*', "1");
        line(item, 2, laborPercent, '*', "0.1");

        Result result = derive().get(item);

        assertEquals(new BigDecimal("100.00"), result.laborSubtotal());
        assertEquals(new BigDecimal("110.00"), result.cost());
    }

    @Test
    void nestedCompositesAreDerivedFirstAndGangsCountAsLabor() {
        UUID oficial = simple(ResourceType.LABOR, "800.00");
        UUID peon = simple(ResourceType.LABOR, "500.00");
        UUID gang = composite(ResourceType.LABOR);
        line(gang, 1, oficial, '*', "1");
        line(gang, 2, peon, '*', "1");
        UUID mortar = composite(ResourceType.MATERIAL);
        UUID cement = simple(ResourceType.MATERIAL, "245.50");
        line(mortar, 1, cement, '*', "0.25"); // 61.375 -> 61.38
        UUID tools = percentage("0.00");
        UUID wall = composite(ResourceType.WORK_ITEM);
        line(wall, 1, mortar, '*', "0.02");   // 1.2276 -> 1.23
        line(wall, 2, gang, '/', "10");       // 130.00
        line(wall, 3, tools, '*', "0.03");    // 3.90

        Map<UUID, Result> results = derive();

        assertEquals(new BigDecimal("1300.00"), results.get(gang).cost());
        assertEquals(new BigDecimal("1300.00"), results.get(gang).laborSubtotal());
        assertEquals(new BigDecimal("61.38"), results.get(mortar).cost());
        assertEquals(new BigDecimal("0.00"), results.get(mortar).laborSubtotal());
        assertEquals(new BigDecimal("130.00"), results.get(wall).laborSubtotal());
        assertEquals(new BigDecimal("135.13"), results.get(wall).cost());
        assertEquals(3, results.size());
    }

    @Test
    void aComponentWithoutCostCountsAsZeroAndMarksEveryAncestorIncomplete() {
        UUID priced = simple(ResourceType.MATERIAL, "10.00");
        UUID unpriced = simple(ResourceType.MATERIAL, null);
        UUID inner = composite(ResourceType.MATERIAL);
        line(inner, 1, unpriced, '*', "2");
        line(inner, 2, priced, '*', "1");
        UUID outer = composite(ResourceType.WORK_ITEM);
        line(outer, 1, inner, '*', "1");
        UUID sibling = composite(ResourceType.WORK_ITEM);
        line(sibling, 1, priced, '*', "3");

        Map<UUID, Result> results = derive();

        assertTrue(results.get(inner).incomplete());
        assertEquals(new BigDecimal("10.00"), results.get(inner).cost());
        assertTrue(results.get(outer).incomplete());
        assertFalse(results.get(sibling).incomplete());
    }

    @Test
    void aCompositeWithoutLinesCostsZero() {
        UUID empty = composite(ResourceType.WORK_ITEM);

        Result result = derive().get(empty);

        assertEquals(new BigDecimal("0.00"), result.cost());
        assertFalse(result.incomplete());
    }

    @Test
    void aSharedComponentIsDerivedOnce() {
        UUID cement = simple(ResourceType.MATERIAL, "100.00");
        UUID mortar = composite(ResourceType.MATERIAL);
        line(mortar, 1, cement, '*', "1");
        UUID a = composite(ResourceType.WORK_ITEM);
        line(a, 1, mortar, '*', "1");
        UUID b = composite(ResourceType.WORK_ITEM);
        line(b, 1, mortar, '*', "2");

        Map<UUID, Result> results = derive();

        assertEquals(new BigDecimal("100.00"), results.get(a).cost());
        assertEquals(new BigDecimal("200.00"), results.get(b).cost());
    }

    @Test
    void aCycleFails() {
        UUID a = composite(ResourceType.WORK_ITEM);
        UUID b = composite(ResourceType.WORK_ITEM);
        line(a, 1, b, '*', "1");
        line(b, 1, a, '*', "1");

        assertThrows(IllegalStateException.class, this::derive);
    }

    @Test
    void aCompositeThatUsesItselfFails() {
        UUID a = composite(ResourceType.WORK_ITEM);
        line(a, 1, a, '*', "1");

        assertThrows(IllegalStateException.class, this::derive);
    }

    @Test
    void aMismatchIsMoreThanFiftyCentavosOrHalfAPercentOfTheImportedCost() {
        assertFalse(DerivedCost.mismatch(new BigDecimal("100.50"), new BigDecimal("100.00")));
        assertTrue(DerivedCost.mismatch(new BigDecimal("100.51"), new BigDecimal("100.00")));
        assertTrue(DerivedCost.mismatch(new BigDecimal("99.49"), new BigDecimal("100.00")));
        assertFalse(DerivedCost.mismatch(new BigDecimal("1005.00"), new BigDecimal("1000.00")));
        assertTrue(DerivedCost.mismatch(new BigDecimal("1005.01"), new BigDecimal("1000.00")));
        assertFalse(DerivedCost.mismatch(new BigDecimal("12.00"), null));
    }
}

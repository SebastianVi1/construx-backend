package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FloorPlanZoneTest {

    @Test
    void squareArea() {
        // 10×10 m square: (0,0),(10,0),(10,10),(0,10)
        List<double[]> square = List.of(
                new double[]{0, 0},
                new double[]{10, 0},
                new double[]{10, 10},
                new double[]{0, 10}
        );
        assertEquals(100.0, FloorPlanZone.computeArea(square), 1e-9);
    }

    @Test
    void squarePerimeter() {
        List<double[]> square = List.of(
                new double[]{0, 0},
                new double[]{10, 0},
                new double[]{10, 10},
                new double[]{0, 10}
        );
        assertEquals(40.0, FloorPlanZone.computePerimeter(square), 1e-9);
    }

    @Test
    void triangleArea() {
        // right triangle with legs 3 and 4 → area = 6
        List<double[]> tri = List.of(
                new double[]{0, 0},
                new double[]{3, 0},
                new double[]{0, 4}
        );
        assertEquals(6.0, FloorPlanZone.computeArea(tri), 1e-9);
    }

    @Test
    void trianglePerimeter() {
        // right triangle 3-4-5 → perimeter = 12
        List<double[]> tri = List.of(
                new double[]{0, 0},
                new double[]{3, 0},
                new double[]{0, 4}
        );
        assertEquals(12.0, FloorPlanZone.computePerimeter(tri), 1e-9);
    }

    @Test
    void twoPointsGiveZeroArea() {
        List<double[]> line = List.of(
                new double[]{0, 0},
                new double[]{5, 0}
        );
        assertEquals(0.0, FloorPlanZone.computeArea(line));
    }

    @Test
    void zoneWithPointsOnALineIsRejected() {
        List<double[]> collinear = List.of(
                new double[]{0, 0},
                new double[]{5, 0},
                new double[]{10, 0}
        );
        RuleViolationException error = assertThrows(RuleViolationException.class, () -> zone(collinear));
        assertEquals("ZONE_NO_AREA", error.getCode());
    }

    @Test
    void zoneFromASingleClickIsRejected() {
        List<double[]> click = List.of(
                new double[]{3, 3},
                new double[]{3, 3},
                new double[]{3, 3},
                new double[]{3, 3}
        );
        assertEquals("ZONE_NO_AREA", assertThrows(RuleViolationException.class, () -> zone(click)).getCode());
    }

    @Test
    void zoneWithANonFinitePointIsRejected() {
        List<double[]> broken = List.of(
                new double[]{0, 0},
                new double[]{Double.NaN, 0},
                new double[]{0, 4}
        );
        assertEquals("ZONE_INVALID_POINT", assertThrows(RuleViolationException.class, () -> zone(broken)).getCode());
    }

    @Test
    void updatingAZoneToNoAreaIsRejectedAndKeepsItsShape() {
        FloorPlanZone zone = zone(List.of(new double[]{0, 0}, new double[]{3, 0}, new double[]{0, 4}));
        List<double[]> flat = List.of(new double[]{0, 0}, new double[]{1, 0}, new double[]{2, 0});

        assertThrows(RuleViolationException.class, () -> zone.update("Flat", null, ZoneStatus.NOT_STARTED, flat));
        assertEquals(6.0, zone.getAreaMSquared(), 1e-9);
    }

    @Test
    void smallButRealZoneIsAccepted() {
        // 20 × 20 cm
        FloorPlanZone zone = zone(List.of(
                new double[]{0, 0}, new double[]{0.2, 0}, new double[]{0.2, 0.2}, new double[]{0, 0.2}));
        assertEquals(0.04, zone.getAreaMSquared(), 1e-9);
    }

    private static FloorPlanZone zone(List<double[]> points) {
        return new FloorPlanZone(null, null, "Zone", null, ZoneStatus.NOT_STARTED, points);
    }
}

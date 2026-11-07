package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlanZone;
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
}

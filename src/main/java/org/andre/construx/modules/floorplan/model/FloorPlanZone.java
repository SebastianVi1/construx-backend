package org.andre.construx.modules.floorplan.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.shared.exception.RuleViolationException;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "floor_plan_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FloorPlanZone {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_plan_id", nullable = false)
    private FloorPlan floorPlan;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ZoneStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "polygon_points", nullable = false, columnDefinition = "jsonb")
    private String polygonPoints;

    @Column(name = "area_m2", nullable = false)
    private double areaMSquared;

    @Column(name = "perimeter_m", nullable = false)
    private double perimeterMeters;

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_membership_id", nullable = false)
    private OrganizationMembership createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    public FloorPlanZone(FloorPlan floorPlan, OrganizationMembership createdBy,
                         String name, String description, ZoneStatus status,
                         List<double[]> points) {
        this.id = UUID.randomUUID();
        this.floorPlan = floorPlan;
        this.createdBy = createdBy;
        this.name = name;
        this.description = description;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        setPoints(points);
    }

    public void update(String name, String description, ZoneStatus status, List<double[]> points) {
        this.name = name;
        this.description = description;
        this.status = status;
        setPoints(points);
        this.updatedAt = Instant.now();
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public List<double[]> parsePoints() {
        return parsePoints(this.polygonPoints);
    }

    /**
     * Smallest area a zone may enclose, in m² (10 × 10 cm). Below it the points sit on a line or on
     * top of each other: a click instead of a drag, or a polygon closed after two corners.
     */
    public static final double MIN_AREA_M2 = 0.01;

    // ---- geometry helpers ----

    public static double computeArea(List<double[]> points) {
        int n = points.size();
        if (n < 3) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double[] cur = points.get(i);
            double[] nxt = points.get((i + 1) % n);
            sum += cur[0] * nxt[1] - nxt[0] * cur[1];
        }
        return Math.abs(sum) / 2.0;
    }

    public static double computePerimeter(List<double[]> points) {
        int n = points.size();
        if (n < 2) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double[] cur = points.get(i);
            double[] nxt = points.get((i + 1) % n);
            double dx = nxt[0] - cur[0];
            double dy = nxt[1] - cur[1];
            sum += Math.sqrt(dx * dx + dy * dy);
        }
        return sum;
    }

    private void setPoints(List<double[]> points) {
        if (points == null || points.size() < 3) {
            throw new RuleViolationException("ZONE_TOO_FEW_POINTS",
                    "A zone polygon must have at least 3 points");
        }
        for (double[] point : points) {
            if (point == null || point.length != 2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                throw new RuleViolationException("ZONE_INVALID_POINT",
                        "Each zone point must be a pair of finite coordinates");
            }
        }
        double area = computeArea(points);
        if (area < MIN_AREA_M2) {
            throw new RuleViolationException("ZONE_NO_AREA",
                    "A zone must enclose an area; its points lie on a line or on top of each other");
        }
        try {
            this.polygonPoints = MAPPER.writeValueAsString(points);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not serialize polygon points", e);
        }
        this.areaMSquared = area;
        this.perimeterMeters = computePerimeter(points);
    }

    static List<double[]> parsePoints(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<double[]>>() {});
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not parse polygon points", e);
        }
    }
}

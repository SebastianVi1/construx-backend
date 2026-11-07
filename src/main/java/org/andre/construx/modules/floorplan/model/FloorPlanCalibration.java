package org.andre.construx.modules.floorplan.model;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Scale calibration for a floor plan. Stores two pixel-space points and the real-world
 * distance between them, from which metersPerPx is derived.
 */
@Entity
@Table(name = "floor_plan_calibration")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FloorPlanCalibration {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_plan_id", nullable = false, unique = true)
    private FloorPlan floorPlan;

    @Column(name = "meters_per_px", nullable = false)
    private double metersPerPx;

    @Column(name = "point_a_x", nullable = false)
    private double pointAX;

    @Column(name = "point_a_y", nullable = false)
    private double pointAY;

    @Column(name = "point_b_x", nullable = false)
    private double pointBX;

    @Column(name = "point_b_y", nullable = false)
    private double pointBY;

    @Column(nullable = false)
    private double lengthMeters;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calibrated_by_membership_id", nullable = false)
    private OrganizationMembership calibratedBy;

    @Column(nullable = false)
    private Instant createdAt;

    public FloorPlanCalibration(FloorPlan floorPlan, OrganizationMembership calibratedBy,
                                double pointAX, double pointAY,
                                double pointBX, double pointBY,
                                double lengthMeters) {
        this.id = UUID.randomUUID();
        this.floorPlan = floorPlan;
        this.calibratedBy = calibratedBy;
        this.pointAX = pointAX;
        this.pointAY = pointAY;
        this.pointBX = pointBX;
        this.pointBY = pointBY;
        this.lengthMeters = lengthMeters;
        double dx = pointBX - pointAX;
        double dy = pointBY - pointAY;
        this.metersPerPx = lengthMeters / Math.sqrt(dx * dx + dy * dy);
        this.createdAt = Instant.now();
    }
}

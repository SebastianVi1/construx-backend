package org.andre.construx.modules.attendance;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneStatus;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.support.Api.Session;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link AttendanceTestBase} plus floor plans: the Site "Tower" has a READY floor plan "Level 1" with the zones
 * "Slab A" and "Slab B". Same Spring context as the base.
 */
public abstract class AttendanceZoneTestBase extends AttendanceTestBase {
    @Autowired protected FloorPlanRepository floorPlans;
    @Autowired protected FloorPlanZoneRepository zoneRows;

    protected UUID planId, slabA, slabB;
    /** Path of the zones a person can check in to. */
    protected String zones;

    @BeforeEach
    void setUpZones() {
        zones = attendance + "/zones";
        planId = floorPlan(siteId, "Level 1");
        slabA = zone(planId, "Slab A");
        slabB = zone(planId, "Slab B");
    }

    /** A READY floor plan of a Site; returns its id. */
    protected UUID floorPlan(UUID site, String name) {
        return new TransactionTemplate(txm).execute(s -> {
            FloorPlan plan = new FloorPlan(projects.findById(site).orElseThrow(),
                    memberships.findById(ownerMembership).orElseThrow(), name, null, null, 1, "image/png", 1000);
            plan.startProcessing();
            plan.markReady("rendered-" + plan.getId() + ".png", Instant.now());
            return floorPlans.save(plan).getId();
        });
    }

    /** A zone drawn on a floor plan; returns its id. */
    protected UUID zone(UUID plan, String name) {
        return new TransactionTemplate(txm).execute(s -> zoneRows.save(new FloorPlanZone(
                floorPlans.findById(plan).orElseThrow(), memberships.findById(ownerMembership).orElseThrow(),
                name, null, ZoneStatus.IN_PROGRESS, List.of(new double[]{0, 0}, new double[]{4, 0},
                new double[]{4, 4}, new double[]{0, 4}))).getId());
    }

    /** The owner deletes a zone through the floor plan API. */
    protected ResultActions deleteZone(UUID site, UUID plan, UUID zone) throws Exception {
        return api.delete(owner, sitePath(site) + "/floor-plans/" + plan + "/zones/" + zone);
    }

    /** The session checks itself in to a zone of the fixture Site; {@code null} is "No zone". */
    protected ResultActions checkIn(Session session, UUID zone) throws Exception {
        return api.post(session, checkIns, zone == null ? "{\"zoneId\":null}" : "{\"zoneId\":\"" + zone + "\"}");
    }
}

package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.AssignmentShift;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ZoneAssignmentRepository extends JpaRepository<ZoneAssignment, UUID> {

    List<ZoneAssignment> findByZoneIdAndClosedAtIsNull(UUID zoneId);

    long countByZoneIdAndClosedAtIsNull(UUID zoneId);

    boolean existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
            UUID membershipId, LocalDate startDate, AssignmentShift shift);

    Optional<ZoneAssignment> findByIdAndZoneId(UUID id, UUID zoneId);

    boolean existsByZoneIdAndMembershipIdAndClosedAtIsNull(UUID zoneId, UUID membershipId);

    @Query("select a from ZoneAssignment a where a.zone.floorPlan.id = :floorPlanId and a.closedAt is null")
    List<ZoneAssignment> findActiveByFloorPlanId(UUID floorPlanId);

    @Query("select a from ZoneAssignment a where a.zone.floorPlan.id = :floorPlanId " +
           "and a.startDate <= :date and a.closedAt is null")
    List<ZoneAssignment> findByFloorPlanIdAndStartDateLessThanEqualAndClosedAtIsNull(
            @Param("floorPlanId") UUID floorPlanId, @Param("date") LocalDate date);
}

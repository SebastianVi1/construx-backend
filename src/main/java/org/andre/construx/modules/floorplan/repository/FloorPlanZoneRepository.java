package org.andre.construx.modules.floorplan.repository;

import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FloorPlanZoneRepository extends JpaRepository<FloorPlanZone, UUID> {

    List<FloorPlanZone> findByFloorPlanIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID floorPlanId);

    Optional<FloorPlanZone> findByIdAndFloorPlanIdAndDeletedAtIsNull(UUID id, UUID floorPlanId);

    /** The live zones of every floor plan of a Site, by floor plan name, then oldest first. */
    @Query("select z from FloorPlanZone z join fetch z.floorPlan p "
            + "where p.project.id = :siteId and z.deletedAt is null "
            + "order by lower(p.name), p.id, z.createdAt, z.id")
    List<FloorPlanZone> findLiveOfSite(@Param("siteId") UUID siteId);

    /** A live zone, only when its floor plan belongs to the Site. */
    @Query("select z from FloorPlanZone z join fetch z.floorPlan p "
            + "where z.id = :id and p.project.id = :siteId and z.deletedAt is null")
    Optional<FloorPlanZone> findLiveOfSite(@Param("id") UUID id, @Param("siteId") UUID siteId);
}

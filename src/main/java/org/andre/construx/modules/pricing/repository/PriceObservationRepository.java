package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.PriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceObservationRepository extends JpaRepository<PriceObservation, UUID> {
    Optional<PriceObservation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Newest first: latest observation day, then latest recorded. */
    @Query("""
            select o from PriceObservation o join fetch o.supplier
            where o.material.id in :materialIds
            order by o.observedOn desc, o.createdAt desc
            """)
    List<PriceObservation> findNewestFirst(@Param("materialIds") Collection<UUID> materialIds);
}

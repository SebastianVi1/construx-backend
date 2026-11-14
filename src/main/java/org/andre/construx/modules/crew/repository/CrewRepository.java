package org.andre.construx.modules.crew.repository;

import org.andre.construx.modules.crew.model.Crew;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrewRepository extends JpaRepository<Crew, UUID> {

    @Query("select c from Crew c join fetch c.leader where c.organization.id = :organizationId order by c.name")
    List<Crew> findByOrganizationId(UUID organizationId);

    /** The crews of the organization one member leads, by name. */
    @Query("select c from Crew c join fetch c.leader where c.organization.id = :organizationId "
            + "and c.leader.id = :leaderId order by c.name")
    List<Crew> findByOrganizationIdAndLeaderId(UUID organizationId, UUID leaderId);

    Optional<Crew> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

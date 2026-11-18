package org.andre.construx.modules.project.repository;

import org.andre.construx.modules.project.model.LaborRateAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LaborRateAssignmentRepository extends JpaRepository<LaborRateAssignment, UUID> {
    /** The history of a person on a Site, newest first. */
    @Query("select r from LaborRateAssignment r where r.organizationId = :organizationId and r.projectId = :projectId "
            + "and r.membershipId = :membershipId order by r.effectiveFrom desc")
    List<LaborRateAssignment> history(UUID organizationId, UUID projectId, UUID membershipId);

    /** The current (latest) row of each of these people on a Site. */
    @Query("select r from LaborRateAssignment r where r.organizationId = :organizationId "
            + "and r.projectId = :projectId and r.membershipId in :membershipIds and r.effectiveFrom = ("
            + "select max(o.effectiveFrom) from LaborRateAssignment o "
            + "where o.projectId = r.projectId and o.membershipId = r.membershipId)")
    List<LaborRateAssignment> currentOf(UUID organizationId, UUID projectId, Collection<UUID> membershipIds);

    /** The latest non-NONE row of a person on any other Site of the organization, newest first. */
    @Query("select r from LaborRateAssignment r where r.organizationId = :organizationId "
            + "and r.membershipId = :membershipId and r.projectId <> :projectId "
            + "and r.kind <> org.andre.construx.modules.project.model.LaborRateKind.NONE "
            + "order by r.effectiveFrom desc")
    List<LaborRateAssignment> elsewhere(UUID organizationId, UUID membershipId, UUID projectId,
                                        org.springframework.data.domain.Pageable page);

    /** Every row of these Sites of the organization, any person, any time. */
    @Query("select r from LaborRateAssignment r where r.organizationId = :organizationId and r.projectId in :projectIds")
    List<LaborRateAssignment> ofProjects(UUID organizationId, Collection<UUID> projectIds);
}

package org.andre.construx.modules.attendance.repository;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {

    /** Every Check-in of a Site and day, oldest first, with the person, the recorder and the zone loaded. */
    @Query("select c from CheckIn c "
            + "join fetch c.membership m join fetch m.userAccount "
            + "join fetch c.recordedBy r join fetch r.userAccount "
            + "left join fetch c.zone z left join fetch z.floorPlan "
            + "where c.site.id = :siteId and c.day = :day order by c.checkedInAt, c.id")
    List<CheckIn> findOfDay(@Param("siteId") UUID siteId, @Param("day") LocalDate day);

    /** One person's Check-ins on a Site and day, latest first. */
    List<CheckIn> findBySiteIdAndMembershipIdAndDayOrderByCheckedInAtDesc(UUID siteId, UUID membershipId,
                                                                          LocalDate day);
}

package org.andre.construx.modules.attendance.repository;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {

    /** Every active (not removed) Check-in of a Site and day, oldest first, with the person and the recorder loaded. */
    @Query("select c from CheckIn c "
            + "join fetch c.membership m join fetch m.userAccount "
            + "join fetch c.recordedBy r join fetch r.userAccount "
            + "where c.site.id = :siteId and c.day = :day and c.removedAt is null order by c.checkedInAt, c.id")
    List<CheckIn> findOfDay(@Param("siteId") UUID siteId, @Param("day") LocalDate day);

    /** One person's active (not removed) Check-ins on a Site and day, latest first. */
    List<CheckIn> findBySiteIdAndMembershipIdAndDayAndRemovedAtIsNullOrderByCheckedInAtDesc(
            UUID siteId, UUID membershipId, LocalDate day);

    /** A Check-in of the Site, removed or not. */
    Optional<CheckIn> findByIdAndSiteId(UUID id, UUID siteId);

    /** Days present and last day present of one person on a Site. */
    record PersonDays(UUID membershipId, long days, LocalDate lastDay) {}

    /** Per person, the distinct days with a Check-in that was not removed, over the whole life of the Site. */
    @Query("select new org.andre.construx.modules.attendance.repository.CheckInRepository$PersonDays("
            + "c.membership.id, count(distinct c.day), max(c.day)) from CheckIn c "
            + "where c.site.id = :siteId and c.removedAt is null group by c.membership.id")
    List<PersonDays> findDaysPerPerson(@Param("siteId") UUID siteId);

    /** How many distinct days of a Site have at least one Check-in that was not removed. */
    @Query("select count(distinct c.day) from CheckIn c where c.site.id = :siteId and c.removedAt is null")
    long countDays(@Param("siteId") UUID siteId);
}

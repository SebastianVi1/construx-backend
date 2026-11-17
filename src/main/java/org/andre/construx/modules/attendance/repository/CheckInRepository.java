package org.andre.construx.modules.attendance.repository;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckInRepository extends JpaRepository<CheckIn, UUID> {

    /**
     * Every active (not removed) Check-in of a Site and day, oldest first, with the person, the recorder and the
     * zone loaded.
     */
    @Query("select c from CheckIn c "
            + "join fetch c.membership m join fetch m.userAccount "
            + "join fetch c.recordedBy r join fetch r.userAccount "
            + "left join fetch c.zone z left join fetch z.floorPlan "
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

    /** One person on one Site on one day. */
    record PersonDayRow(UUID projectId, UUID membershipId, LocalDate day) {}

    /** Distinct (Site, person, day) with a Check-in that was not removed, on these Sites, over their whole life. */
    @Query("select distinct new org.andre.construx.modules.attendance.repository.CheckInRepository$PersonDayRow("
            + "c.site.id, c.membership.id, c.day) from CheckIn c "
            + "where c.site.id in :siteIds and c.removedAt is null")
    List<PersonDayRow> findPersonDays(@Param("siteIds") Collection<UUID> siteIds);

    /** Distinct (Site, person, day) with a Check-in that was not removed, on a Site, with {@code from <= day <= to}. */
    @Query("select distinct new org.andre.construx.modules.attendance.repository.CheckInRepository$PersonDayRow("
            + "c.site.id, c.membership.id, c.day) from CheckIn c "
            + "where c.site.id = :siteId and c.removedAt is null and c.day >= :from and c.day <= :to")
    List<PersonDayRow> findPersonDays(@Param("siteId") UUID siteId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    /** The first day of a Site with a Check-in that was not removed, or null. */
    @Query("select min(c.day) from CheckIn c where c.site.id = :siteId and c.removedAt is null")
    LocalDate findFirstDay(@Param("siteId") UUID siteId);
}

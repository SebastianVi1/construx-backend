package org.andre.construx.modules.attendance.service;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.andre.construx.modules.attendance.repository.CheckInRepository.PersonDays;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.time.LocalDay;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance of a Site: who is on it on one day, built from that day's Check-ins (ADR 0007). The only way into
 * the module for the controller and for other modules. Check-ins are not audited: the table is itself the log.
 */
@Service
public class AttendanceService {
    private static final Comparator<PersonRow> BY_NAME =
            Comparator.comparing((PersonRow row) -> row.name(), String.CASE_INSENSITIVE_ORDER);

    private final CheckInRepository checkIns;
    private final PersonDirectory directory;
    private final MembershipDirectory memberships;
    private final LocalDay localDay;
    private final Clock clock;

    public AttendanceService(CheckInRepository checkIns, PersonDirectory directory, MembershipDirectory memberships,
                             LocalDay localDay, Clock clock) {
        this.checkIns = checkIns;
        this.directory = directory;
        this.memberships = memberships;
        this.localDay = localDay;
        this.clock = clock;
    }

    public enum State { PRESENT }

    /** A member as Attendance shows them; {@code name} is the profile name, or the email without one. */
    public record PersonRef(UUID membershipId, String name) {}

    public record CheckInData(UUID id, UUID membershipId, LocalDate date, Instant checkedInAt, PersonRef recordedBy) {}

    /**
     * One person of the day; {@code checkIn} is their current Check-in, the latest one not removed.
     * {@code removable} tells whether the caller may remove it now.
     */
    public record PersonRow(UUID membershipId, String name, URI avatarUrl, State state, CheckInData checkIn,
                            boolean removable) {}

    /** What Attendance says about the caller. */
    public record Me(UUID membershipId) {}

    /** {@code today} tells whether {@code date} is the current day, the only one that accepts Check-ins. */
    public record AttendanceData(LocalDate date, boolean today, List<PersonRow> people, Me me) {}

    /** Today's Attendance of the Site, one row per person, by name. */
    @Transactional(readOnly = true)
    public AttendanceData attendance(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        LocalDate day = localDay.today();
        List<CheckIn> ofDay = checkIns.findOfDay(site.siteId(), day);
        Names names = names(ofDay);
        boolean today = day.equals(localDay.today());

        // Oldest first, so the latest Check-in of a person is the one left in the map.
        Map<UUID, CheckIn> current = new LinkedHashMap<>();
        for (CheckIn checkIn : ofDay) {
            current.put(checkIn.getMembership().getId(), checkIn);
        }
        List<PersonRow> people = current.values().stream()
                .map(checkIn -> {
                    OrganizationMembership member = checkIn.getMembership();
                    return new PersonRow(member.getId(), names.name(member), names.avatarUrl(member),
                            State.PRESENT, names.data(checkIn), today && mayRemove(site, checkIn));
                })
                .sorted(BY_NAME)
                .toList();
        return new AttendanceData(day, today, people, new Me(site.membershipId()));
    }

    /**
     * Records the caller as present on the Site today. Repeating it changes nothing: the Check-in already made
     * today is returned and no second one is stored.
     */
    @Transactional
    public CheckInData checkIn(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        OrganizationMembership caller = site.membership();
        Instant now = clock.instant();
        LocalDate day = localDay.of(now);

        CheckIn checkIn = checkIns.findBySiteIdAndMembershipIdAndDayAndRemovedAtIsNullOrderByCheckedInAtDesc(
                        site.siteId(), caller.getId(), day).stream()
                .findFirst()
                .orElseGet(() -> checkIns.save(new CheckIn(site.site(), caller, day, now, caller)));
        return names(List.of(checkIn)).data(checkIn);
    }

    /** One person of the summary; {@code name} is the profile name, or the email without one. */
    public record PersonSummary(UUID membershipId, String name, long daysPresent, LocalDate lastDayPresent) {}

    /**
     * Attendance of a Site over its whole life. {@code averagePeoplePerDay} is person-days over
     * {@code daysWithCheckIns}, to one decimal, and null when there are no Check-ins. {@code people} has most
     * days present first, then by name.
     */
    public record AttendanceSummary(long daysWithCheckIns, BigDecimal averagePeoplePerDay,
                                    List<PersonSummary> people) {}

    /**
     * The Site's Attendance to date, for the Site Report. A person counts once per day however many Check-ins
     * they have, removed Check-ins are ignored, and people who have left the company keep their name.
     */
    @Transactional(readOnly = true)
    public AttendanceSummary summary(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        List<PersonDays> perPerson = checkIns.findDaysPerPerson(site.siteId());
        if (perPerson.isEmpty()) return new AttendanceSummary(0, null, List.of());

        Map<UUID, OrganizationMembership> members = new LinkedHashMap<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        for (OrganizationMembership member : memberships.byIds(site.organizationId(),
                perPerson.stream().map(PersonDays::membershipId).toList())) {
            members.put(member.getId(), member);
            userIds.add(member.getUserAccount().getId());
        }
        Names names = new Names(directory.byUserId(userIds));

        List<PersonSummary> people = perPerson.stream()
                .map(p -> new PersonSummary(p.membershipId(), names.name(members.get(p.membershipId())),
                        p.days(), p.lastDay()))
                .sorted(Comparator.comparingLong(PersonSummary::daysPresent).reversed()
                        .thenComparing(PersonSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        long days = checkIns.countDays(site.siteId());
        long personDays = people.stream().mapToLong(PersonSummary::daysPresent).sum();
        BigDecimal average = BigDecimal.valueOf(personDays).divide(BigDecimal.valueOf(days), 1, RoundingMode.HALF_UP);
        return new AttendanceSummary(days, average, people);
    }

    /**
     * Removes a Check-in made by mistake: it is kept, marked with who removed it and when, and ignored from then
     * on, so the person falls back to their previous Check-in of the day or is no longer present. Allowed to
     * the member who recorded it and to members with {@code MANAGE_PLAN}, only on the day it belongs to
     * ({@link org.andre.construx.modules.attendance.model.CheckInLockedException} otherwise). Removing an
     * already removed Check-in changes nothing.
     */
    @Transactional
    public void remove(SiteScope site, UUID checkInId) {
        site.require(PermissionAction.VIEW_PROJECT);
        CheckIn checkIn = checkIns.findByIdAndSiteId(checkInId, site.siteId())
                .orElseThrow(() -> new ResourceNotFoundException("Check-in not found"));
        if (!mayRemove(site, checkIn)) {
            throw new AccessDeniedException("Only who recorded the Check-in or a plan manager can remove it");
        }
        Instant now = clock.instant();
        checkIn.remove(site.membership(), now, localDay.of(now));
    }

    private static boolean mayRemove(SiteScope site, CheckIn checkIn) {
        return checkIn.getRecordedBy().getId().equals(site.membershipId()) || site.can(PermissionAction.MANAGE_PLAN);
    }

    /** Display names and photos of everyone in a batch of Check-ins, read once. */
    private record Names(Map<UUID, Person> persons) {
        String name(OrganizationMembership member) {
            String fullName = PersonDirectory.of(persons, member.getUserAccount().getId()).fullName();
            return fullName == null || fullName.isBlank() ? member.getUserAccount().getEmail() : fullName;
        }

        URI avatarUrl(OrganizationMembership member) {
            return PersonDirectory.of(persons, member.getUserAccount().getId()).avatarUrl();
        }

        CheckInData data(CheckIn checkIn) {
            OrganizationMembership recorder = checkIn.getRecordedBy();
            return new CheckInData(checkIn.getId(), checkIn.getMembership().getId(), checkIn.getDay(),
                    checkIn.getCheckedInAt(), new PersonRef(recorder.getId(), name(recorder)));
        }
    }

    private Names names(List<CheckIn> batch) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (CheckIn checkIn : batch) {
            userIds.add(checkIn.getMembership().getUserAccount().getId());
            userIds.add(checkIn.getRecordedBy().getUserAccount().getId());
        }
        return new Names(userIds.isEmpty() ? Map.of() : directory.byUserId(userIds));
    }
}

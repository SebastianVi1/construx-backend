package org.andre.construx.modules.attendance.service;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.andre.construx.modules.attendance.repository.CheckInRepository.PersonDays;
import org.andre.construx.modules.crew.service.CrewDirectory;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.service.SiteZones;
import org.andre.construx.modules.floorplan.service.SiteZones.ZoneRef;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.MembershipStatus;
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
import java.util.ArrayList;
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
    private final CrewDirectory crews;
    private final SiteZones siteZones;
    private final LocalDay localDay;
    private final Clock clock;

    public AttendanceService(CheckInRepository checkIns, PersonDirectory directory, MembershipDirectory memberships,
                             CrewDirectory crews, SiteZones siteZones, LocalDay localDay, Clock clock) {
        this.checkIns = checkIns;
        this.directory = directory;
        this.memberships = memberships;
        this.crews = crews;
        this.siteZones = siteZones;
        this.localDay = localDay;
        this.clock = clock;
    }

    /**
     * A person of the day against the zone plan. {@code PRESENT}: checked in and expected. {@code ABSENT}:
     * expected and not checked in; derived, never stored (ADR 0007). {@code UNPLANNED}: checked in without being
     * expected.
     */
    public enum State { PRESENT, ABSENT, UNPLANNED }

    /** A member as Attendance shows them; {@code name} is the profile name, or the email without one. */
    public record PersonRef(UUID membershipId, String name) {}

    /** {@code zone} is null for present with no zone; a deleted zone keeps its name here. */
    public record CheckInData(UUID id, UUID membershipId, ZoneRef zone, LocalDate date, Instant checkedInAt,
                              PersonRef recordedBy) {}

    /**
     * {@code membershipId} null or absent: the caller. {@code zoneId} null or absent: no zone.
     */
    public record CheckInRequest(UUID membershipId, UUID zoneId) {}

    /**
     * One person of the day. {@code planned} tells whether the zone plan expected them and {@code expectedZone}
     * where (null when not planned). {@code checkIn} is their current Check-in, the latest one not removed (null
     * when absent); its zone is their current zone. {@code removable} tells whether the caller may remove it now.
     */
    public record PersonRow(UUID membershipId, String name, URI avatarUrl, State state, boolean planned,
                            ZoneRef expectedZone, CheckInData checkIn, boolean removable) {}

    /**
     * What Attendance says about the caller; {@code expectedZone} is where the plan expects them that day and
     * {@code canCheckInOthers} whether they may record a Check-in for another member (a plan manager, or a Crew
     * leader for their own Crew).
     */
    public record Me(UUID membershipId, ZoneRef expectedZone, boolean canCheckInOthers,
                     List<RollCallCrew> rollCallCrews) {}

    /** A Crew member and their current Check-in, if already present today. */
    public record RollCallMember(UUID membershipId, String name, CheckInData checkIn) {}

    /** A Crew the caller may take roll for today. */
    public record RollCallCrew(UUID crewId, String name, ZoneRef expectedZone, List<RollCallMember> members) {}

    /** Members to record in one atomic roll call. */
    public record RollCallRequest(UUID crewId, List<UUID> membershipIds, UUID zoneId) {}

    /** {@code today} tells whether {@code date} is the current day, the only one that accepts Check-ins. */
    public record AttendanceData(LocalDate date, boolean today, List<PersonRow> people, Me me) {}

    /**
     * The Attendance of the Site on a day ({@code date} null: today), one row per person, by name: everyone with
     * a Check-in that day and everyone the zone plan expected. A past day is read as it stands now; a future one
     * is refused. An expected member who is no longer active and has no Check-in is left out, not absent.
     */
    @Transactional(readOnly = true)
    public AttendanceData attendance(SiteScope site, LocalDate date) {
        site.require(PermissionAction.VIEW_PROJECT);
        LocalDate currentDay = localDay.today();
        LocalDate day = date == null ? currentDay : date;
        if (day.isAfter(currentDay)) {
            throw new IllegalArgumentException("date cannot be after today");
        }
        boolean today = day.equals(currentDay);

        // Oldest first, so the latest Check-in of a person is the one left in the map.
        Map<UUID, CheckIn> current = new LinkedHashMap<>();
        for (CheckIn checkIn : checkIns.findOfDay(site.siteId(), day)) {
            current.put(checkIn.getMembership().getId(), checkIn);
        }
        Map<UUID, ZoneRef> expected = new LinkedHashMap<>();
        for (SiteZones.Expected person : siteZones.expected(site.siteId(), day)) {
            expected.put(person.membershipId(), person.zone());
        }
        List<OrganizationMembership> missing = memberships.byIds(site.organizationId(),
                        expected.keySet().stream().filter(id -> !current.containsKey(id)).toList()).stream()
                .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
                .toList();

        Set<UUID> userIds = new LinkedHashSet<>();
        for (CheckIn checkIn : current.values()) {
            userIds.add(checkIn.getMembership().getUserAccount().getId());
            userIds.add(checkIn.getRecordedBy().getUserAccount().getId());
        }
        for (OrganizationMembership member : missing) {
            userIds.add(member.getUserAccount().getId());
        }
        Names names = new Names(userIds.isEmpty() ? Map.of() : directory.byUserId(userIds));

        List<PersonRow> people = new ArrayList<>();
        for (CheckIn checkIn : current.values()) {
            OrganizationMembership member = checkIn.getMembership();
            ZoneRef expectedZone = expected.get(member.getId());
            boolean planned = expected.containsKey(member.getId());
            people.add(new PersonRow(member.getId(), names.name(member), names.avatarUrl(member),
                    planned ? State.PRESENT : State.UNPLANNED, planned, expectedZone, names.data(checkIn),
                    today && mayRemove(site, checkIn)));
        }
        for (OrganizationMembership member : missing) {
            people.add(new PersonRow(member.getId(), names.name(member), names.avatarUrl(member),
                    State.ABSENT, true, expected.get(member.getId()), null, false));
        }
        people.sort(BY_NAME);
        List<RollCallCrew> rollCallCrews = today ? rollCallCrews(site, day) : List.of();
        return new AttendanceData(day, today, List.copyOf(people),
                new Me(site.membershipId(), expected.get(site.membershipId()), mayCheckInOthers(site), rollCallCrews));
    }

    /** The zones a person can check in to: the live zones of the Site's floor plans. */
    @Transactional(readOnly = true)
    public List<ZoneRef> zones(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        return siteZones.ofSite(site.siteId());
    }

    /**
     * Records the caller, or another active member of the company, as present on the Site today, in a zone or in
     * none. The recorder is always the caller. A later Check-in to another zone (or to none) moves the person and
     * the earlier ones stay as history. Checking in to where the person's current Check-in (the latest of today
     * that was not removed) already puts them changes nothing: that Check-in is returned and none is stored.
     */
    @Transactional
    public CheckInData checkIn(SiteScope site, CheckInRequest request) {
        site.require(PermissionAction.VIEW_PROJECT);
        OrganizationMembership caller = site.membership();
        OrganizationMembership person = person(site, request.membershipId());
        FloorPlanZone zone = request.zoneId() == null ? null : siteZones.live(site.siteId(), request.zoneId());
        Instant now = clock.instant();
        LocalDate day = localDay.of(now);
        CheckIn checkIn = record(site, caller, person, zone, now, day);
        return names(List.of(checkIn)).data(checkIn);
    }

    /** Records one Crew roll call after validating every requested member and the zone. */
    @Transactional
    public List<CheckInData> rollCall(SiteScope site, RollCallRequest request) {
        site.require(PermissionAction.VIEW_PROJECT);
        if (request == null || request.crewId() == null || request.membershipIds() == null
                || request.membershipIds().isEmpty() || request.membershipIds().contains(null)) {
            throw new IllegalArgumentException("crewId and at least one membershipId are required");
        }

        CrewDirectory.CrewRoster crew = crews.roster(site.organizationId(), request.crewId());
        boolean planManager = site.can(PermissionAction.MANAGE_PLAN);
        if (!planManager && !crew.leaderId().equals(site.membershipId())) {
            throw new AccessDeniedException("Only the Crew leader or a plan manager can take roll");
        }

        Instant now = clock.instant();
        LocalDate day = localDay.of(now);
        SiteZones.ExpectedCrew expectedCrew = siteZones.expectedCrews(site.siteId(), day).stream()
                .filter(expected -> expected.crewId().equals(crew.id()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Crew is not expected on this Site today"));
        FloorPlanZone zone = request.zoneId() == null ? null : siteZones.live(site.siteId(), request.zoneId());

        List<OrganizationMembership> people = new ArrayList<>();
        for (UUID membershipId : new LinkedHashSet<>(request.membershipIds())) {
            if (!crew.memberIds().contains(membershipId)) {
                throw new IllegalArgumentException("Every roll-call member must belong to the Crew");
            }
            people.add(person(site, membershipId));
        }

        OrganizationMembership caller = site.membership();
        List<CheckIn> checkInsForCrew = people.stream()
                .map(person -> record(site, caller, person, zone, now, day))
                .toList();
        Names names = names(checkInsForCrew);
        return checkInsForCrew.stream().map(names::data).toList();
    }

    private CheckIn record(SiteScope site, OrganizationMembership caller, OrganizationMembership person,
                           FloorPlanZone zone, Instant now, LocalDate day) {
        return checkIns.findBySiteIdAndMembershipIdAndDayAndRemovedAtIsNullOrderByCheckedInAtDesc(
                        site.siteId(), person.getId(), day).stream()
                .findFirst()
                .filter(latest -> latest.isIn(zone))
                .orElseGet(() -> checkIns.save(new CheckIn(site.site(), person, zone, day, now, caller)));
    }

    private List<RollCallCrew> rollCallCrews(SiteScope site, LocalDate day) {
        Map<UUID, CheckIn> current = new LinkedHashMap<>();
        for (CheckIn checkIn : checkIns.findOfDay(site.siteId(), day)) {
            current.put(checkIn.getMembership().getId(), checkIn);
        }
        List<SiteZones.ExpectedCrew> expectedCrews = siteZones.expectedCrews(site.siteId(), day);
        List<CrewDirectory.CrewRoster> eligible = new ArrayList<>();
        boolean planManager = site.can(PermissionAction.MANAGE_PLAN);
        for (SiteZones.ExpectedCrew expected : expectedCrews) {
            CrewDirectory.CrewRoster crew = crews.roster(site.organizationId(), expected.crewId());
            if (planManager || crew.leaderId().equals(site.membershipId())) eligible.add(crew);
        }
        Set<UUID> memberIds = new LinkedHashSet<>();
        eligible.forEach(crew -> memberIds.addAll(crew.memberIds()));
        Map<UUID, OrganizationMembership> activeMembers = new LinkedHashMap<>();
        memberships.byIds(site.organizationId(), memberIds).stream()
                .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
                .forEach(member -> activeMembers.put(member.getId(), member));

        Set<UUID> userIds = new LinkedHashSet<>();
        activeMembers.values().forEach(member -> userIds.add(member.getUserAccount().getId()));
        current.values().forEach(checkIn -> {
            userIds.add(checkIn.getMembership().getUserAccount().getId());
            userIds.add(checkIn.getRecordedBy().getUserAccount().getId());
        });
        Names names = new Names(userIds.isEmpty() ? Map.of() : directory.byUserId(userIds));
        Map<UUID, CheckInData> currentData = new LinkedHashMap<>();
        current.forEach((id, checkIn) -> currentData.put(id, names.data(checkIn)));

        List<RollCallCrew> result = new ArrayList<>();
        for (SiteZones.ExpectedCrew expected : expectedCrews) {
            CrewDirectory.CrewRoster crew = eligible.stream().filter(c -> c.id().equals(expected.crewId()))
                    .findFirst().orElse(null);
            if (crew == null) continue;
            List<RollCallMember> crewMembers = crew.memberIds().stream()
                    .filter(activeMembers::containsKey)
                    .map(id -> new RollCallMember(id, names.name(activeMembers.get(id)), currentData.get(id)))
                    .toList();
            result.add(new RollCallCrew(crew.id(), crew.name(), expected.zone(), crewMembers));
        }
        return List.copyOf(result);
    }

    /**
     * Who the Check-in is for: the caller, or another active member of the company. A member who is unknown,
     * suspended or has left, or who belongs to another company, is a 404; a caller who may not check that person
     * in is a 403.
     */
    private OrganizationMembership person(SiteScope site, UUID membershipId) {
        if (membershipId == null || membershipId.equals(site.membershipId())) return site.membership();
        OrganizationMembership person = memberships.find(site.organizationId(), membershipId)
                .filter(member -> member.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (!mayCheckInOther(site, person)) {
            throw new AccessDeniedException(
                    "Only a plan manager or the leader of the person's Crew can check in another member");
        }
        return person;
    }

    /** A plan manager checks in anyone; a Crew leader only the members of the Crews they lead. */
    private boolean mayCheckInOther(SiteScope site, OrganizationMembership person) {
        if (site.can(PermissionAction.MANAGE_PLAN)) return true;
        return crews.ledBy(site.organizationId(), site.membershipId()).stream()
                .anyMatch(crew -> crew.memberIds().contains(person.getId()));
    }

    /** Whether the caller may check in anybody at all, which is what the Attendance page needs to offer it. */
    private boolean mayCheckInOthers(SiteScope site) {
        return site.can(PermissionAction.MANAGE_PLAN)
                || !crews.ledBy(site.organizationId(), site.membershipId()).isEmpty();
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
            ZoneRef zone = checkIn.getZone() == null ? null : SiteZones.ref(checkIn.getZone());
            return new CheckInData(checkIn.getId(), checkIn.getMembership().getId(), zone, checkIn.getDay(),
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

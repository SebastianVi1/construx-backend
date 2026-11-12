package org.andre.construx.modules.attendance.service;

import org.andre.construx.modules.attendance.model.CheckIn;
import org.andre.construx.modules.attendance.repository.CheckInRepository;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.service.SiteZones;
import org.andre.construx.modules.floorplan.service.SiteZones.ZoneRef;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.shared.time.LocalDay;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final SiteZones siteZones;
    private final LocalDay localDay;
    private final Clock clock;

    public AttendanceService(CheckInRepository checkIns, PersonDirectory directory, SiteZones siteZones,
                             LocalDay localDay, Clock clock) {
        this.checkIns = checkIns;
        this.directory = directory;
        this.siteZones = siteZones;
        this.localDay = localDay;
        this.clock = clock;
    }

    public enum State { PRESENT }

    /** A member as Attendance shows them; {@code name} is the profile name, or the email without one. */
    public record PersonRef(UUID membershipId, String name) {}

    /** {@code zone} is null for present with no zone; a deleted zone keeps its name here. */
    public record CheckInData(UUID id, UUID membershipId, ZoneRef zone, LocalDate date, Instant checkedInAt,
                              PersonRef recordedBy) {}

    /** {@code zoneId} null or absent: no zone. */
    public record CheckInRequest(UUID zoneId) {}

    /** One person of the day; {@code checkIn} is their current (latest) Check-in. */
    public record PersonRow(UUID membershipId, String name, URI avatarUrl, State state, CheckInData checkIn) {}

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

        // Oldest first, so the latest Check-in of a person is the one left in the map.
        Map<UUID, CheckIn> current = new LinkedHashMap<>();
        for (CheckIn checkIn : ofDay) {
            current.put(checkIn.getMembership().getId(), checkIn);
        }
        List<PersonRow> people = current.values().stream()
                .map(checkIn -> {
                    OrganizationMembership member = checkIn.getMembership();
                    return new PersonRow(member.getId(), names.name(member), names.avatarUrl(member),
                            State.PRESENT, names.data(checkIn));
                })
                .sorted(BY_NAME)
                .toList();
        return new AttendanceData(day, true, people, new Me(site.membershipId()));
    }

    /** The zones a person can check in to: the live zones of the Site's floor plans. */
    @Transactional(readOnly = true)
    public List<ZoneRef> zones(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        return siteZones.ofSite(site.siteId());
    }

    /**
     * Records the caller as present on the Site today, in a zone or in none. A later Check-in to another zone
     * (or to none) moves the person and the earlier ones stay as history. Checking in to where the person's
     * latest Check-in of today already puts them changes nothing: that Check-in is returned and none is stored.
     */
    @Transactional
    public CheckInData checkIn(SiteScope site, CheckInRequest request) {
        site.require(PermissionAction.VIEW_PROJECT);
        FloorPlanZone zone = request.zoneId() == null ? null : siteZones.live(site.siteId(), request.zoneId());
        OrganizationMembership caller = site.membership();
        Instant now = clock.instant();
        LocalDate day = localDay.of(now);

        CheckIn checkIn = checkIns.findBySiteIdAndMembershipIdAndDayOrderByCheckedInAtDesc(
                        site.siteId(), caller.getId(), day).stream()
                .findFirst()
                .filter(latest -> latest.isIn(zone))
                .orElseGet(() -> checkIns.save(new CheckIn(site.site(), caller, zone, day, now, caller)));
        return names(List.of(checkIn)).data(checkIn);
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

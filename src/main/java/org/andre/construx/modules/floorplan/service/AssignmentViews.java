package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.CrewMember;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.service.Occupant.EquipmentInfo;
import org.andre.construx.modules.floorplan.service.Occupant.Kind;
import org.andre.construx.modules.floorplan.service.Occupant.Worker;
import org.andre.construx.modules.floorplan.service.ZoneAssignmentService.AssignmentData;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ActivityService.OpenTask;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one mapper from assignments to their response, occupant included. Names, photos, roles and open tasks of
 * every person in the batch are read with one query each, whatever the number of assignments.
 */
@Component
public class AssignmentViews {
    private static final Comparator<Worker> LEADER_THEN_NAME = Comparator.comparing((Worker w) -> !w.crewLeader())
            .thenComparing(Worker::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final OrganizationMembershipRepository memberships;
    private final PersonDirectory directory;
    private final ActivityService activities;

    public AssignmentViews(OrganizationMembershipRepository memberships, PersonDirectory directory,
                    ActivityService activities) {
        this.memberships = memberships;
        this.directory = directory;
        this.activities = activities;
    }

    AssignmentData describe(SiteScope site, ZoneAssignment assignment) {
        return describe(site, List.of(assignment)).get(0);
    }

    List<AssignmentData> describe(SiteScope site, List<ZoneAssignment> assignments) {
        People people = people(site, assignments);
        return assignments.stream().map(a -> toData(a, occupant(a, people))).toList();
    }

    private record People(Map<UUID, OrganizationMembership> memberships, Map<UUID, Person> persons,
                          Map<UUID, List<OpenTask>> openTasks) {
        Worker worker(UUID membershipId, boolean crewLeader) {
            OrganizationMembership m = memberships.get(membershipId);
            Person person = m == null ? new Person(null, null) : PersonDirectory.of(persons, m.getUserAccount().getId());
            return new Worker(membershipId, person.fullName(), person.avatarUrl(),
                    m == null ? null : m.getRole().getName(), crewLeader,
                    openTasks.getOrDefault(membershipId, List.of()));
        }
    }

    private People people(SiteScope site, List<ZoneAssignment> assignments) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (ZoneAssignment a : assignments) {
            if (a.getMembership() != null) ids.add(a.getMembership().getId());
            if (a.getCrew() != null) a.getCrew().getMembers().forEach(cm -> ids.add(cm.getMembershipId()));
        }
        if (ids.isEmpty()) return new People(Map.of(), Map.of(), Map.of());
        Map<UUID, OrganizationMembership> byId = memberships.findAllById(ids).stream()
                .collect(Collectors.toMap(OrganizationMembership::getId, Function.identity()));
        Map<UUID, Person> persons = directory.byUserId(byId.values().stream()
                .map(m -> m.getUserAccount().getId()).toList());
        List<UUID> tasksVisible = ids.stream().filter(id -> site.can(PermissionAction.VIEW_ACTIVITY, id)).toList();
        return new People(byId, persons, activities.openTasksOf(site.siteId(), tasksVisible));
    }

    private static Occupant occupant(ZoneAssignment a, People people) {
        if (a.getMembership() != null) {
            Worker w = people.worker(a.getMembership().getId(), false);
            return new Occupant(Kind.PERSON, w.membershipId(), w.fullName(), w.avatarUrl(), List.of(w), null);
        }
        if (a.getCrew() != null) {
            Crew crew = a.getCrew();
            UUID leaderId = crew.getLeader().getId();
            List<Worker> crewMembers = crew.getMembers().stream()
                    .map(CrewMember::getMembershipId)
                    .map(id -> people.worker(id, id.equals(leaderId)))
                    .sorted(LEADER_THEN_NAME)
                    .toList();
            return new Occupant(Kind.CREW, crew.getId(), crew.getName(), null, crewMembers, null);
        }
        if (a.getEquipment() != null) {
            Equipment eq = a.getEquipment();
            return new Occupant(Kind.EQUIPMENT, eq.getId(), eq.getType() + " — " + eq.getIdentifier(), null,
                    List.of(), new EquipmentInfo(eq.getType(), eq.getIdentifier()));
        }
        return null;
    }

    private static AssignmentData toData(ZoneAssignment a, Occupant occupant) {
        return new AssignmentData(
                a.getId(),
                a.getZone().getId(),
                a.getMembership() != null ? a.getMembership().getId() : null,
                a.getCrew() != null ? a.getCrew().getId() : null,
                a.getEquipment() != null ? a.getEquipment().getId() : null,
                a.getActivity(),
                a.getStartDate(),
                a.getEndDate(),
                a.getShift(),
                a.getCreatedAt(),
                a.getClosedAt(),
                occupant);
    }
}

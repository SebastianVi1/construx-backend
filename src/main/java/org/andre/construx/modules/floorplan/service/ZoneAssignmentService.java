package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.floorplan.model.AssignmentShift;
import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.repository.ZoneAssignmentRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.SiteScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ZoneAssignmentService {

    private final ZoneAssignmentRepository assignments;
    private final FloorPlanLookup lookup;
    private final OrganizationMembershipRepository memberships;
    private final CrewRepository crews;
    private final EquipmentRepository equipment;
    private final AuditService auditService;

    public ZoneAssignmentService(ZoneAssignmentRepository assignments,
                                  FloorPlanLookup lookup,
                                  OrganizationMembershipRepository memberships,
                                  CrewRepository crews,
                                  EquipmentRepository equipment,
                                  AuditService auditService) {
        this.assignments = assignments;
        this.lookup = lookup;
        this.memberships = memberships;
        this.crews = crews;
        this.equipment = equipment;
        this.auditService = auditService;
    }

    public record AssignmentData(
            UUID id,
            UUID zoneId,
            UUID membershipId,
            UUID crewId,
            UUID equipmentId,
            String activity,
            LocalDate startDate,
            LocalDate endDate,
            AssignmentShift shift,
            Instant createdAt,
            Instant closedAt
    ) {}

    public record CreateAssignmentResponse(AssignmentData assignment, boolean hasOverlapWarning) {}

    public record CreateAssignmentRequest(
            UUID membershipId,
            UUID crewId,
            UUID equipmentId,
            String activity,
            LocalDate startDate,
            LocalDate endDate,
            AssignmentShift shift
    ) {}

    public record ReassignRequest(UUID newZoneId) {}

    @Transactional
    public CreateAssignmentResponse createAssignment(SiteScope site, UUID floorPlanId, UUID zoneId,
                                                      CreateAssignmentRequest request) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership assignedBy = site.membership();
        UUID organizationId = site.organizationId();
        FloorPlanZone zone = lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        OrganizationMembership membership = null;
        Crew crew = null;
        Equipment equip = null;

        if (request.membershipId() != null) {
            membership = memberships.findByIdAndOrganizationId(request.membershipId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
        } else if (request.crewId() != null) {
            crew = crews.findByIdAndOrganizationId(request.crewId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Crew not found"));
        } else if (request.equipmentId() != null) {
            equip = equipment.findByIdAndOrganizationId(request.equipmentId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Equipment not found"));
        }

        // A plain drag and drop sends no date or shift: the assignment starts today for the full day
        LocalDate startDate = request.startDate() != null ? request.startDate() : LocalDate.now();
        AssignmentShift shift = request.shift() != null ? request.shift() : AssignmentShift.FULL_DAY;

        boolean hasOverlapWarning = false;
        if (membership != null) {
            hasOverlapWarning = assignments.existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
                    membership.getId(), startDate, shift);
        } else if (crew != null) {
            hasOverlapWarning = crew.getMembers().stream()
                    .anyMatch(cm -> assignments.existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
                            cm.getMembershipId(), startDate, shift));
        }

        ZoneAssignment assignment = assignments.save(new ZoneAssignment(
                zone, membership, crew, equip,
                request.activity(), startDate, request.endDate(),
                shift, assignedBy));

        auditService.record("ZoneAssignment", assignment.getId(), "CREATE",
                assignedBy.getId(), null, assignmentSnapshot(assignment));
        return new CreateAssignmentResponse(toData(assignment), hasOverlapWarning);
    }

    @Transactional
    public AssignmentData closeAssignment(SiteScope site, UUID floorPlanId, UUID zoneId, UUID assignmentId) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership actor = site.membership();
        lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        ZoneAssignment assignment = assignments.findByIdAndZoneId(assignmentId, zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        Map<String, Object> before = assignmentSnapshot(assignment);
        assignment.close();
        auditService.record("ZoneAssignment", assignment.getId(), "CLOSE",
                actor.getId(), before, assignmentSnapshot(assignment));
        return toData(assignment);
    }

    @Transactional
    public AssignmentData reassignToZone(SiteScope site, UUID floorPlanId, UUID zoneId, UUID assignmentId,
                                          UUID newZoneId) {
        site.require(PermissionAction.MANAGE_PLAN);
        OrganizationMembership assignedBy = site.membership();
        FloorPlan plan = lookup.floorPlan(site, floorPlanId);
        lookup.zone(plan, zoneId);
        FloorPlanZone targetZone = lookup.zone(plan, newZoneId);

        ZoneAssignment old = assignments.findByIdAndZoneId(assignmentId, zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        old.close();

        ZoneAssignment newAssignment = assignments.save(new ZoneAssignment(
                targetZone,
                old.getMembership(),
                old.getCrew(),
                old.getEquipment(),
                old.getActivity(),
                old.getStartDate(),
                old.getEndDate(),
                old.getShift(),
                assignedBy));

        auditService.record("ZoneAssignment", old.getId(), "MOVE",
                assignedBy.getId(),
                Map.of("oldZoneId", zoneId.toString()),
                Map.of("newZoneId", newZoneId.toString(), "newAssignmentId", newAssignment.getId().toString()));
        return toData(newAssignment);
    }

    @Transactional(readOnly = true)
    public List<AssignmentData> listAssignments(SiteScope site, UUID floorPlanId, UUID zoneId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.zone(lookup.floorPlan(site, floorPlanId), zoneId);

        return assignments.findByZoneIdAndClosedAtIsNull(zoneId).stream()
                .map(this::toData)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentData> listFloorPlanAssignments(SiteScope site, UUID floorPlanId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.floorPlan(site, floorPlanId);

        return assignments.findActiveByFloorPlanId(floorPlanId).stream()
                .map(this::toData)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getZoneAssignmentCounts(SiteScope site, UUID floorPlanId) {
        site.require(PermissionAction.VIEW_PLAN);
        lookup.floorPlan(site, floorPlanId);

        return assignments.findActiveByFloorPlanId(floorPlanId).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getZone().getId().toString(),
                        Collectors.counting()));
    }

    private AssignmentData toData(ZoneAssignment a) {
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
                a.getClosedAt()
        );
    }

    private Map<String, Object> assignmentSnapshot(ZoneAssignment a) {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("id", a.getId().toString());
        snap.put("zoneId", a.getZone().getId().toString());
        snap.put("activity", a.getActivity());
        snap.put("startDate", a.getStartDate() != null ? a.getStartDate().toString() : null);
        snap.put("endDate", a.getEndDate() != null ? a.getEndDate().toString() : null);
        snap.put("shift", a.getShift() != null ? a.getShift().name() : null);
        snap.put("closedAt", a.getClosedAt() != null ? a.getClosedAt().toString() : null);
        return snap;
    }
}

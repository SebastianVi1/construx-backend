package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.floorplan.model.AssignmentShift;
import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanZone;
import org.andre.construx.modules.floorplan.model.ZoneAssignment;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanZoneRepository;
import org.andre.construx.modules.floorplan.repository.ZoneAssignmentRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
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
    private final FloorPlanZoneRepository zones;
    private final FloorPlanRepository floorPlans;
    private final OrganizationMembershipRepository memberships;
    private final CrewRepository crews;
    private final EquipmentRepository equipment;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final AuditService auditService;

    public ZoneAssignmentService(ZoneAssignmentRepository assignments,
                                  FloorPlanZoneRepository zones,
                                  FloorPlanRepository floorPlans,
                                  OrganizationMembershipRepository memberships,
                                  CrewRepository crews,
                                  EquipmentRepository equipment,
                                  ProjectService projects,
                                  MembershipGuard guard,
                                  DomainAuthorization authorization,
                                  AuditService auditService) {
        this.assignments = assignments;
        this.zones = zones;
        this.floorPlans = floorPlans;
        this.memberships = memberships;
        this.crews = crews;
        this.equipment = equipment;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
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
    public CreateAssignmentResponse createAssignment(UUID userId, UUID organizationId, UUID projectId,
                                                      UUID floorPlanId, UUID zoneId,
                                                      CreateAssignmentRequest request) {
        OrganizationMembership assignedBy = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        requireFloorPlan(floorPlanId, projectId);
        FloorPlanZone zone = loadZone(zoneId, floorPlanId);

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

        boolean hasOverlapWarning = false;
        if (membership != null) {
            hasOverlapWarning = assignments.existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
                    membership.getId(), request.startDate(), request.shift());
        }

        ZoneAssignment assignment = assignments.save(new ZoneAssignment(
                zone, membership, crew, equip,
                request.activity(), request.startDate(), request.endDate(),
                request.shift(), assignedBy));

        auditService.record("ZoneAssignment", assignment.getId(), "CREATE",
                assignedBy.getId(), null, assignmentSnapshot(assignment));
        return new CreateAssignmentResponse(toData(assignment), hasOverlapWarning);
    }

    @Transactional
    public AssignmentData closeAssignment(UUID userId, UUID organizationId, UUID projectId,
                                           UUID floorPlanId, UUID zoneId, UUID assignmentId) {
        OrganizationMembership actor = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        requireFloorPlan(floorPlanId, projectId);
        loadZone(zoneId, floorPlanId);

        ZoneAssignment assignment = assignments.findByIdAndZoneId(assignmentId, zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        Map<String, Object> before = assignmentSnapshot(assignment);
        assignment.close();
        auditService.record("ZoneAssignment", assignment.getId(), "CLOSE",
                actor.getId(), before, assignmentSnapshot(assignment));
        return toData(assignment);
    }

    @Transactional
    public AssignmentData reassignToZone(UUID userId, UUID organizationId, UUID projectId,
                                          UUID floorPlanId, UUID zoneId, UUID assignmentId,
                                          UUID newZoneId) {
        OrganizationMembership assignedBy = guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        requireFloorPlan(floorPlanId, projectId);
        loadZone(zoneId, floorPlanId);
        FloorPlanZone targetZone = loadZone(newZoneId, floorPlanId);

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
    public List<AssignmentData> listAssignments(UUID userId, UUID organizationId, UUID projectId,
                                                 UUID floorPlanId, UUID zoneId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        requireFloorPlan(floorPlanId, projectId);
        loadZone(zoneId, floorPlanId);

        return assignments.findByZoneIdAndClosedAtIsNull(zoneId).stream()
                .map(this::toData)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getZoneAssignmentCounts(UUID userId, UUID organizationId, UUID projectId,
                                                      UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);

        requireFloorPlan(floorPlanId, projectId);

        return assignments.findActiveByFloorPlanId(floorPlanId).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getZone().getId().toString(),
                        Collectors.counting()));
    }

    private FloorPlan requireFloorPlan(UUID floorPlanId, UUID projectId) {
        return floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
    }

    private FloorPlanZone loadZone(UUID zoneId, UUID floorPlanId) {
        return zones.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));
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

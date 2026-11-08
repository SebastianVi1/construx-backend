package org.andre.construx.modules.floorplan;

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
import org.andre.construx.modules.floorplan.service.ZoneAssignmentService;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.audit.AuditService;
import org.andre.construx.shared.security.DomainAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ZoneAssignmentServiceTest {

    private ZoneAssignmentRepository assignmentRepo;
    private FloorPlanZoneRepository zoneRepo;
    private FloorPlanRepository floorPlanRepo;
    private OrganizationMembershipRepository membershipRepo;
    private CrewRepository crewRepo;
    private EquipmentRepository equipmentRepo;
    private ProjectService projects;
    private MembershipGuard guard;
    private DomainAuthorization authorization;

    private ZoneAssignmentService service;

    private UUID userId, orgId, projectId, floorPlanId, zoneId;
    private OrganizationMembership member;
    private FloorPlan floorPlan;
    private FloorPlanZone zone;

    @BeforeEach
    void setUp() {
        assignmentRepo = mock(ZoneAssignmentRepository.class);
        zoneRepo = mock(FloorPlanZoneRepository.class);
        floorPlanRepo = mock(FloorPlanRepository.class);
        membershipRepo = mock(OrganizationMembershipRepository.class);
        crewRepo = mock(CrewRepository.class);
        equipmentRepo = mock(EquipmentRepository.class);
        projects = mock(ProjectService.class);
        guard = mock(MembershipGuard.class);
        authorization = mock(DomainAuthorization.class);

        AuditService auditService = mock(AuditService.class);
        service = new ZoneAssignmentService(assignmentRepo, zoneRepo, floorPlanRepo,
                membershipRepo, crewRepo, equipmentRepo, projects, guard, authorization, auditService);

        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        floorPlanId = UUID.randomUUID();
        zoneId = UUID.randomUUID();

        member = mock(OrganizationMembership.class);
        when(member.getId()).thenReturn(UUID.randomUUID());

        floorPlan = mock(FloorPlan.class);
        zone = mock(FloorPlanZone.class);
        when(zone.getId()).thenReturn(zoneId);

        when(guard.requireActive(any(), any())).thenReturn(member);
        when(floorPlanRepo.findByIdAndProjectId(floorPlanId, projectId)).thenReturn(Optional.of(floorPlan));
        when(zoneRepo.findByIdAndFloorPlanIdAndDeletedAtIsNull(zoneId, floorPlanId)).thenReturn(Optional.of(zone));
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createAssignment_noOverlap_warningFalse() {
        UUID membershipId = UUID.randomUUID();
        OrganizationMembership membership = mock(OrganizationMembership.class);
        when(membership.getId()).thenReturn(membershipId);
        when(membershipRepo.findByIdAndOrganizationId(membershipId, orgId)).thenReturn(Optional.of(membership));
        when(assignmentRepo.existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
                membershipId, LocalDate.of(2025, 1, 15), AssignmentShift.MORNING)).thenReturn(false);

        ZoneAssignmentService.CreateAssignmentRequest request = new ZoneAssignmentService.CreateAssignmentRequest(
                membershipId, null, null, "Foundation work", LocalDate.of(2025, 1, 15), null, AssignmentShift.MORNING);

        ZoneAssignmentService.CreateAssignmentResponse response =
                service.createAssignment(userId, orgId, projectId, floorPlanId, zoneId, request);

        assertFalse(response.hasOverlapWarning());
        assertNotNull(response.assignment());
    }

    @Test
    void createAssignment_withOverlap_warningTrue() {
        UUID membershipId = UUID.randomUUID();
        OrganizationMembership membership = mock(OrganizationMembership.class);
        when(membership.getId()).thenReturn(membershipId);
        when(membershipRepo.findByIdAndOrganizationId(membershipId, orgId)).thenReturn(Optional.of(membership));
        when(assignmentRepo.existsByMembershipIdAndStartDateAndShiftAndClosedAtIsNull(
                membershipId, LocalDate.of(2025, 1, 15), AssignmentShift.MORNING)).thenReturn(true);

        ZoneAssignmentService.CreateAssignmentRequest request = new ZoneAssignmentService.CreateAssignmentRequest(
                membershipId, null, null, "Foundation work", LocalDate.of(2025, 1, 15), null, AssignmentShift.MORNING);

        ZoneAssignmentService.CreateAssignmentResponse response =
                service.createAssignment(userId, orgId, projectId, floorPlanId, zoneId, request);

        assertTrue(response.hasOverlapWarning());
        assertNotNull(response.assignment());
        // Assignment must still be created despite the warning
        verify(assignmentRepo).save(any(ZoneAssignment.class));
    }

    @Test
    void reassignToZone_closesOldAndCreatesNew() {
        UUID newZoneId = UUID.randomUUID();
        FloorPlanZone newZone = mock(FloorPlanZone.class);
        when(newZone.getId()).thenReturn(newZoneId);
        when(zoneRepo.findByIdAndFloorPlanIdAndDeletedAtIsNull(newZoneId, floorPlanId)).thenReturn(Optional.of(newZone));

        UUID assignmentId = UUID.randomUUID();
        ZoneAssignment old = mock(ZoneAssignment.class);
        when(old.getMembership()).thenReturn(member);
        when(old.getCrew()).thenReturn(null);
        when(old.getEquipment()).thenReturn(null);
        when(old.getActivity()).thenReturn("Pouring concrete");
        when(old.getStartDate()).thenReturn(LocalDate.of(2025, 2, 1));
        when(old.getEndDate()).thenReturn(null);
        when(old.getShift()).thenReturn(AssignmentShift.FULL_DAY);
        when(assignmentRepo.findByIdAndZoneId(assignmentId, zoneId)).thenReturn(Optional.of(old));

        service.reassignToZone(userId, orgId, projectId, floorPlanId, zoneId, assignmentId, newZoneId);

        verify(old).close();
        verify(assignmentRepo, times(1)).save(any(ZoneAssignment.class));
    }

    @Test
    void listFloorPlanAssignments_returnsActiveAssignmentsOfEveryZone() {
        ZoneAssignment active = new ZoneAssignment(zone, member, null, null, "Framing",
                LocalDate.of(2025, 3, 1), null, AssignmentShift.MORNING, member);
        when(assignmentRepo.findActiveByFloorPlanId(floorPlanId)).thenReturn(java.util.List.of(active));

        var result = service.listFloorPlanAssignments(userId, orgId, projectId, floorPlanId);

        assertEquals(1, result.size());
        assertEquals(zoneId, result.get(0).zoneId());
        assertEquals(member.getId(), result.get(0).membershipId());
    }

    @Test
    void createAssignment_withoutDetails_startsTodayForTheFullDay() {
        UUID membershipId = UUID.randomUUID();
        OrganizationMembership membership = mock(OrganizationMembership.class);
        when(membership.getId()).thenReturn(membershipId);
        when(membershipRepo.findByIdAndOrganizationId(membershipId, orgId)).thenReturn(Optional.of(membership));

        ZoneAssignmentService.CreateAssignmentRequest request = new ZoneAssignmentService.CreateAssignmentRequest(
                membershipId, null, null, null, null, null, null);

        ZoneAssignmentService.CreateAssignmentResponse response =
                service.createAssignment(userId, orgId, projectId, floorPlanId, zoneId, request);

        assertNull(response.assignment().activity());
        assertEquals(LocalDate.now(), response.assignment().startDate());
        assertEquals(AssignmentShift.FULL_DAY, response.assignment().shift());
    }
}

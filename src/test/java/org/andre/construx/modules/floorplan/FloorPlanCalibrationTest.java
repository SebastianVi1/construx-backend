package org.andre.construx.modules.floorplan;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanCalibration;
import org.andre.construx.modules.floorplan.repository.FloorPlanCalibrationRepository;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.floorplan.service.FloorPlanCalibrationService;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests for FloorPlanCalibrationService: metersPerPx computation and validation. */
class FloorPlanCalibrationTest {

    private FloorPlanCalibrationRepository calibrationRepo;
    private FloorPlanRepository floorPlanRepo;
    private MembershipGuard guard;
    private DomainAuthorization authorization;
    private ProjectService projectService;
    private FloorPlanCalibrationService service;

    private UUID orgId;
    private UUID projectId;
    private UUID userId;
    private UUID floorPlanId;

    private FloorPlan readyPlan;
    private OrganizationMembership member;

    @BeforeEach
    void setUp() {
        calibrationRepo = mock(FloorPlanCalibrationRepository.class);
        floorPlanRepo = mock(FloorPlanRepository.class);
        guard = mock(MembershipGuard.class);
        authorization = mock(DomainAuthorization.class);
        projectService = mock(ProjectService.class);

        service = new FloorPlanCalibrationService(calibrationRepo, floorPlanRepo,
                projectService, guard, authorization);

        orgId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        userId = UUID.randomUUID();
        floorPlanId = UUID.randomUUID();

        Organization org = mock(Organization.class);
        when(org.getId()).thenReturn(orgId);

        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(project.getOrganization()).thenReturn(org);

        member = mock(OrganizationMembership.class);
        when(guard.requireActive(userId, orgId)).thenReturn(member);

        readyPlan = new FloorPlan(project, member, "Level 1", null, "Ground", 1, "image/png", 1024L);
        readyPlan.startProcessing();
        readyPlan.markReady("orgs/x/rendered.png", java.time.Instant.now());

        when(floorPlanRepo.findByIdAndProjectId(any(), eq(projectId))).thenReturn(Optional.of(readyPlan));
        when(calibrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void metersPerPxComputedCorrectly() {
        // 100 px horizontal distance, 5.0 m → 0.05 m/px
        FloorPlanCalibrationService.CalibrationData result = service.save(
                userId, orgId, projectId, floorPlanId,
                0, 0, 100, 0, 5.0);

        assertEquals(0.05, result.metersPerPx(), 1e-10);
    }

    @Test
    void metersPerPxWithDiagonalDistance() {
        // 3-4-5 right triangle: pixel distance = 5, 10 m → 2 m/px
        FloorPlanCalibrationService.CalibrationData result = service.save(
                userId, orgId, projectId, floorPlanId,
                0, 0, 3, 4, 10.0);

        assertEquals(2.0, result.metersPerPx(), 1e-10);
    }

    @Test
    void zeroDistanceRejected() {
        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> service.save(userId, orgId, projectId, floorPlanId,
                        10, 20, 10, 20, 5.0));
        assertEquals("CALIBRATION_ZERO_DISTANCE", ex.getCode());
    }

    @Test
    void notReadyPlanRejected() {
        Organization org2 = mock(Organization.class);
        when(org2.getId()).thenReturn(orgId);
        Project proj2 = mock(Project.class);
        when(proj2.getId()).thenReturn(projectId);
        when(proj2.getOrganization()).thenReturn(org2);
        FloorPlan pending = new FloorPlan(proj2, member, "Pending", null, null, 1, "image/png", 1024L);

        when(floorPlanRepo.findByIdAndProjectId(any(), eq(projectId))).thenReturn(Optional.of(pending));

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> service.save(userId, orgId, projectId, floorPlanId,
                        0, 0, 100, 0, 5.0));
        assertEquals("FLOOR_PLAN_NOT_READY", ex.getCode());
    }
}

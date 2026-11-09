package org.andre.construx.modules.crew;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.crew.service.CrewService;
import org.andre.construx.modules.crew.service.EquipmentService;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopeFixtures;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CrewServiceTest {

    private CrewRepository crewRepo;
    private EquipmentRepository equipmentRepo;
    private MembershipDirectory membershipRepo;
    private OrgScope scope;

    private CrewService crewService;
    private EquipmentService equipmentService;

    private UUID orgId;
    private Organization org;
    private OrganizationMembership leaderMembership;
    private OrganizationMembership otherMembership;

    @BeforeEach
    void setUp() {
        crewRepo = mock(CrewRepository.class);
        equipmentRepo = mock(EquipmentRepository.class);
        membershipRepo = mock(MembershipDirectory.class);

        crewService = new CrewService(crewRepo, membershipRepo);
        equipmentService = new EquipmentService(equipmentRepo);

        orgId = UUID.randomUUID();
        org = mock(Organization.class);
        when(org.getId()).thenReturn(orgId);
        leaderMembership = mock(OrganizationMembership.class);
        otherMembership = mock(OrganizationMembership.class);
        UUID leaderId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(leaderMembership.getId()).thenReturn(leaderId);
        when(leaderMembership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(otherMembership.getId()).thenReturn(otherId);
        when(otherMembership.getStatus()).thenReturn(MembershipStatus.ACTIVE);

        when(leaderMembership.getOrganization()).thenReturn(org);
        when(membershipRepo.find(orgId, leaderId)).thenReturn(Optional.of(leaderMembership));
        when(membershipRepo.find(orgId, otherId)).thenReturn(Optional.of(otherMembership));
        scope = OrgScopeFixtures.fullAccess(orgId, leaderMembership);
    }

    @Test
    void removingLeaderIsRejected() {
        Crew crew = new Crew(org, "Test Crew", leaderMembership);
        UUID crewId = crew.getId();
        when(crewRepo.findByIdAndOrganizationId(crewId, orgId)).thenReturn(Optional.of(crew));
        when(crewRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RuleViolationException ex = assertThrows(RuleViolationException.class,
                () -> crewService.removeMember(scope, crewId, leaderMembership.getId()));
        assertEquals("CREW_LEADER_CANNOT_BE_REMOVED", ex.getCode());
    }

    @Test
    void nonLeaderMemberCanBeRemoved() {
        Crew crew = new Crew(org, "Test Crew", leaderMembership);
        crew.addMember(otherMembership);
        UUID crewId = crew.getId();
        when(crewRepo.findByIdAndOrganizationId(crewId, orgId)).thenReturn(Optional.of(crew));
        when(membershipRepo.find(orgId, otherMembership.getId()))
                .thenReturn(Optional.of(otherMembership));

        assertDoesNotThrow(() -> crewService.removeMember(scope, crewId, otherMembership.getId()));
    }

    @Test
    void equipmentDeactivatedSetsActiveFalse() {
        Equipment equipment = new Equipment(org, "Excavator", "EX-001", null);
        UUID equipId = equipment.getId();
        when(equipmentRepo.findByIdAndOrganizationId(equipId, orgId)).thenReturn(Optional.of(equipment));

        assertTrue(equipment.isActive());
        equipmentService.deactivateEquipment(scope, equipId);
        assertFalse(equipment.isActive());
    }

    @Test
    void equipmentUpdateChangesFields() {
        Equipment equipment = new Equipment(org, "Crane", "CR-01", 10000L);
        UUID equipId = equipment.getId();
        when(equipmentRepo.findByIdAndOrganizationId(equipId, orgId)).thenReturn(Optional.of(equipment));

        EquipmentService.EquipmentData result = equipmentService.updateEquipment(
                scope, equipId, "Tower Crane", null, 20000L, null);

        assertEquals("Tower Crane", result.type());
        assertEquals("CR-01", result.identifier());
        assertEquals(20000L, result.dailyCostCents());
        assertTrue(result.active());
    }
}

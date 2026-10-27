package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class OrganizationServiceTest {
    private OrganizationRepository organizations;
    private OrganizationMembershipRepository memberships;
    private DomainAuthorization authorization;
    private OrganizationService service;
    private Organization organization;
    private OrganizationMembership membership;
    private UUID userId, orgId;

    @BeforeEach
    void setUp() {
        organizations = mock(OrganizationRepository.class);
        memberships = mock(OrganizationMembershipRepository.class);
        authorization = mock(DomainAuthorization.class);
        service = new OrganizationService(mock(UserAccountRepository.class), organizations, memberships, authorization,
                new MembershipGuard(memberships));
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        organization = mock(Organization.class);
        membership = mock(OrganizationMembership.class);
        when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, userId)).thenReturn(Optional.of(membership));
        when(organizations.findById(orgId)).thenReturn(Optional.of(organization));
    }

    @Test
    void memberWithoutManagePermissionCannotRename() {
        doThrow(new AccessDeniedException("Permission required")).when(authorization)
                .require(userId, orgId, null, null, PermissionAction.MANAGE_ORGANIZATION);
        assertThrows(AccessDeniedException.class, () -> service.rename(userId, orgId, "New name"));
        verify(organization, never()).rename(any());
    }

    @Test
    void renameChecksTheManageOrganizationPermission() {
        service.rename(userId, orgId, "New name");
        verify(authorization).require(eq(userId), eq(orgId), isNull(), isNull(), eq(PermissionAction.MANAGE_ORGANIZATION));
        verify(organization).rename("New name");
    }

    @Test
    void nonMemberSeesNotFoundAndNeverReachesAuthorization() {
        UUID stranger = UUID.randomUUID();
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, stranger)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.get(stranger, orgId));
        assertThrows(ResourceNotFoundException.class, () -> service.rename(stranger, orgId, "Hijacked"));
        verifyNoInteractions(authorization);
        verify(organization, never()).rename(any());
    }

    @Test
    void suspendedMemberSeesNotFound() {
        when(membership.getStatus()).thenReturn(MembershipStatus.SUSPENDED);
        assertThrows(ResourceNotFoundException.class, () -> service.get(userId, orgId));
    }
}

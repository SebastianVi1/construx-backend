package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.access.OrgScopeFixtures;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Non-member and suspended-member 404s happen while building the scope; see AccessScopesTest. */
class OrganizationServiceTest {
    private OrganizationRepository organizations;
    private OrganizationService service;
    private Organization organization;
    private OrganizationMembership membership;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        organizations = mock(OrganizationRepository.class);
        service = new OrganizationService(mock(AccountService.class), organizations,
                mock(OrganizationMembershipRepository.class));
        orgId = UUID.randomUUID();
        organization = mock(Organization.class);
        membership = mock(OrganizationMembership.class);
        when(membership.getOrganization()).thenReturn(organization);
        when(membership.getRole()).thenReturn(mock(OrganizationRole.class));
        when(organizations.findById(orgId)).thenReturn(Optional.of(organization));
    }

    @Test
    void memberWithoutManagePermissionCannotRename() {
        OrgScope member = OrgScopeFixtures.org(orgId, membership, Map.of());
        assertThrows(AccessDeniedException.class, () -> service.rename(member, "New name"));
        verify(organization, never()).rename(any());
    }

    @Test
    void manageOrganizationAtProjectScopeIsNotEnoughToRename() {
        OrgScope member = OrgScopeFixtures.org(orgId, membership,
                Map.of(PermissionAction.MANAGE_ORGANIZATION, PermissionScope.PROJECT));
        assertThrows(AccessDeniedException.class, () -> service.rename(member, "New name"));
        verify(organization, never()).rename(any());
    }

    @Test
    void ownerRenamesTheOrganization() {
        service.rename(OrgScopeFixtures.fullAccess(orgId, membership), "New name");
        verify(organization).rename("New name");
    }
}

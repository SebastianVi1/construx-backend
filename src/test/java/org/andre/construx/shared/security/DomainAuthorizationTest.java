package org.andre.construx.shared.security;

import org.andre.construx.modules.organization.model.*;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainAuthorizationTest {
    private OrganizationMembershipRepository memberships;
    private ProjectRepository projects;
    private ProjectAccessRepository accesses;
    private DomainAuthorization authorization;
    private OrganizationMembership member;
    private Permission permission;
    private Project project;
    private UUID userId, orgId, projectId, memberId;

    @BeforeEach
    void setUp() {
        memberships = mock(OrganizationMembershipRepository.class);
        projects = mock(ProjectRepository.class);
        accesses = mock(ProjectAccessRepository.class);
        authorization = new DomainAuthorization(memberships, projects, accesses);
        userId = UUID.randomUUID(); orgId = UUID.randomUUID();
        projectId = UUID.randomUUID(); memberId = UUID.randomUUID();
        member = mock(OrganizationMembership.class);
        OrganizationRole role = mock(OrganizationRole.class);
        permission = mock(Permission.class);
        project = mock(Project.class);
        Organization organization = mock(Organization.class);
        when(member.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(member.getId()).thenReturn(memberId);
        when(member.getRole()).thenReturn(role);
        when(role.getPermissions()).thenReturn(Set.of(permission));
        when(permission.getAction()).thenReturn(PermissionAction.VIEW_PROJECT);
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, userId)).thenReturn(Optional.of(member));
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(project.getOrganization()).thenReturn(organization);
        when(organization.getId()).thenReturn(orgId);
    }

    @Test
    void projectScopeRequiresExplicitAccess() {
        when(permission.getScope()).thenReturn(PermissionScope.PROJECT);
        assertThrows(AccessDeniedException.class,
                () -> authorization.require(userId, orgId, projectId, null, PermissionAction.VIEW_PROJECT));
        when(accesses.existsByProjectIdAndOrganizationMembershipId(projectId, memberId)).thenReturn(true);
        assertDoesNotThrow(() -> authorization.require(userId, orgId, projectId, null, PermissionAction.VIEW_PROJECT));
    }

    @Test
    void suspendedMembershipIsDenied() {
        when(member.getStatus()).thenReturn(MembershipStatus.SUSPENDED);
        assertThrows(AccessDeniedException.class,
                () -> authorization.require(userId, orgId, projectId, null, PermissionAction.VIEW_PROJECT));
    }

    @Test
    void organizationPermissionStillRejectsAnotherOrganizationsProject() {
        when(permission.getScope()).thenReturn(PermissionScope.ORGANIZATION);
        when(project.getOrganization().getId()).thenReturn(UUID.randomUUID());
        assertThrows(AccessDeniedException.class,
                () -> authorization.require(userId, orgId, projectId, null, PermissionAction.VIEW_PROJECT));
    }
}

package org.andre.construx.shared.security;

import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.Permission;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.andre.construx.modules.organization.model.PermissionAction.MANAGE_ACTIVITY;
import static org.andre.construx.modules.organization.model.PermissionAction.MANAGE_MEMBERS;
import static org.andre.construx.modules.organization.model.PermissionAction.MANAGE_PLAN;
import static org.andre.construx.modules.organization.model.PermissionAction.VIEW_PLAN;
import static org.andre.construx.modules.organization.model.PermissionScope.ORGANIZATION;
import static org.andre.construx.modules.organization.model.PermissionScope.OWN;
import static org.andre.construx.modules.organization.model.PermissionScope.PROJECT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessScopesTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID siteId = UUID.randomUUID();

    private OrganizationMembershipRepository memberships;
    private ProjectRepository projects;
    private ProjectAccessRepository accesses;
    private AccessScopes scopes;

    @BeforeEach
    void setUp() {
        memberships = mock(OrganizationMembershipRepository.class);
        projects = mock(ProjectRepository.class);
        accesses = mock(ProjectAccessRepository.class);
        scopes = new AccessScopes(memberships, projects, accesses);
    }

    @Test
    void nonMemberGetsNotFoundForTheSite() {
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, userId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> scopes.site(userId, orgId, siteId));
    }

    @Test
    void suspendedMemberGetsNotFound() {
        member(MembershipStatus.SUSPENDED, grant(VIEW_PLAN, ORGANIZATION));
        assertThrows(ResourceNotFoundException.class, () -> scopes.site(userId, orgId, siteId));
        assertThrows(ResourceNotFoundException.class, () -> scopes.org(userId, orgId));
    }

    @Test
    void siteOfAnotherOrganizationIsNotFoundEvenWithoutPermission() {
        member(MembershipStatus.ACTIVE);
        when(projects.findByIdAndOrganizationId(siteId, orgId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> scopes.site(userId, orgId, siteId));
    }

    @Test
    void scopeExposesTheLoadedMembershipAndSite() {
        OrganizationMembership membership = member(MembershipStatus.ACTIVE);
        Project project = site();
        SiteScope scope = scopes.site(userId, orgId, siteId);
        assertSame(membership, scope.membership());
        assertSame(project, scope.site());
        assertEquals(orgId, scope.organizationId());
        assertEquals(siteId, scope.siteId());
        assertEquals(userId, scope.userId());
    }

    @Test
    void missingPermissionIsForbidden() {
        member(MembershipStatus.ACTIVE, grant(VIEW_PLAN, ORGANIZATION));
        site();
        SiteScope scope = scopes.site(userId, orgId, siteId);
        assertDoesNotThrow(() -> scope.require(VIEW_PLAN));
        assertThrows(AccessDeniedException.class, () -> scope.require(MANAGE_PLAN));
    }

    @Test
    void organizationScopeNeedsNoSiteAccess() {
        member(MembershipStatus.ACTIVE, grant(MANAGE_PLAN, ORGANIZATION));
        site();
        scopes.site(userId, orgId, siteId).require(MANAGE_PLAN);
        verify(accesses, never()).existsByProjectIdAndOrganizationMembershipId(any(), any());
    }

    @Test
    void projectScopeWithoutSiteAccessIsForbidden() {
        OrganizationMembership membership = member(MembershipStatus.ACTIVE, grant(VIEW_PLAN, PROJECT));
        site();
        when(accesses.existsByProjectIdAndOrganizationMembershipId(siteId, membership.getId())).thenReturn(false);
        SiteScope scope = scopes.site(userId, orgId, siteId);
        assertThrows(AccessDeniedException.class, () -> scope.require(VIEW_PLAN));
    }

    @Test
    void projectScopeWithSiteAccessIsAllowedAndQueriesAccessOnce() {
        OrganizationMembership membership = member(MembershipStatus.ACTIVE,
                grant(VIEW_PLAN, PROJECT), grant(MANAGE_PLAN, PROJECT));
        site();
        when(accesses.existsByProjectIdAndOrganizationMembershipId(siteId, membership.getId())).thenReturn(true);
        SiteScope scope = scopes.site(userId, orgId, siteId);
        scope.require(VIEW_PLAN);
        scope.require(MANAGE_PLAN);
        verify(accesses, times(1)).existsByProjectIdAndOrganizationMembershipId(siteId, membership.getId());
    }

    @Test
    void ownScopeOnlyCoversTheCallersOwnRows() {
        OrganizationMembership membership = member(MembershipStatus.ACTIVE, grant(MANAGE_ACTIVITY, OWN));
        site();
        SiteScope scope = scopes.site(userId, orgId, siteId);
        assertDoesNotThrow(() -> scope.require(MANAGE_ACTIVITY, membership.getId()));
        assertThrows(AccessDeniedException.class, () -> scope.require(MANAGE_ACTIVITY, UUID.randomUUID()));
        assertThrows(AccessDeniedException.class, () -> scope.require(MANAGE_ACTIVITY));
    }

    @Test
    void scopeOfReportsTheGrantForListFiltering() {
        member(MembershipStatus.ACTIVE, grant(VIEW_PLAN, PROJECT));
        site();
        SiteScope scope = scopes.site(userId, orgId, siteId);
        assertEquals(Optional.of(PROJECT), scope.scopeOf(VIEW_PLAN));
        assertEquals(Optional.empty(), scope.scopeOf(MANAGE_PLAN));
    }

    @Test
    void orgScopeRequiresOrganizationWideGrant() {
        member(MembershipStatus.ACTIVE, grant(MANAGE_MEMBERS, ORGANIZATION), grant(VIEW_PLAN, PROJECT));
        OrgScope scope = scopes.org(userId, orgId);
        assertDoesNotThrow(() -> scope.require(MANAGE_MEMBERS));
        assertThrows(AccessDeniedException.class, () -> scope.require(VIEW_PLAN));
        assertThrows(AccessDeniedException.class, () -> scope.require(MANAGE_PLAN));
    }

    @Test
    void nonMemberGetsNotFoundForTheOrganization() {
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, userId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> scopes.org(userId, orgId));
    }

    private OrganizationMembership member(MembershipStatus status, Permission... grants) {
        OrganizationMembership membership = mock(OrganizationMembership.class);
        OrganizationRole role = mock(OrganizationRole.class);
        when(membership.getId()).thenReturn(UUID.randomUUID());
        when(membership.getStatus()).thenReturn(status);
        when(membership.getRole()).thenReturn(role);
        when(role.getPermissions()).thenReturn(Arrays.stream(grants).collect(Collectors.toSet()));
        when(memberships.findByOrganizationIdAndUserAccountId(orgId, userId)).thenReturn(Optional.of(membership));
        return membership;
    }

    private Project site() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(siteId);
        when(projects.findByIdAndOrganizationId(siteId, orgId)).thenReturn(Optional.of(project));
        return project;
    }

    private static Permission grant(PermissionAction action, PermissionScope scope) {
        Permission permission = mock(Permission.class);
        when(permission.getAction()).thenReturn(action);
        when(permission.getScope()).thenReturn(scope);
        return permission;
    }
}

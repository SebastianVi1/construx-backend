package org.andre.construx.modules.organization.access;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.shared.exception.ForbiddenRuleException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

import static org.andre.construx.modules.organization.model.PermissionAction.*;
import static org.andre.construx.modules.organization.model.PermissionScope.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleAuthorityTest {
    private Organization organization;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("owner@acme.com", "hash"));
        orgId = UUID.randomUUID();
    }

    @Test
    void administratorCannotGrantBudget() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        assertFalse(RoleAuthority.fitsWithin(admin, Map.of(MANAGE_BUDGET, ORGANIZATION)));
        assertTrue(RoleAuthority.fitsWithin(admin, Map.of(VIEW_BUDGET, ORGANIZATION)));
    }

    @Test
    void ownerFitsEverything() {
        OrgScope owner = callerWith("OWNER");
        for (OrganizationRole role : organization.getRoles()) {
            assertTrue(RoleAuthority.fitsWithin(owner, role.grants()), role.getCode());
        }
    }

    @Test
    void projectScopeDoesNotCoverOrganization() {
        OrgScope caller = scope(member("WORKER"), Map.of(VIEW_PROJECT, PROJECT, VIEW_PLAN, PROJECT));
        assertFalse(RoleAuthority.fitsWithin(caller, Map.of(VIEW_PLAN, ORGANIZATION)));
        assertTrue(RoleAuthority.fitsWithin(caller, Map.of(VIEW_PLAN, PROJECT)));
    }

    @Test
    void ownCoveredByProject() {
        OrgScope caller = scope(member("WORKER"), Map.of(MANAGE_ACTIVITY, PROJECT));
        assertTrue(RoleAuthority.fitsWithin(caller, Map.of(MANAGE_ACTIVITY, OWN)));
        assertTrue(RoleAuthority.fitsWithin(caller, Map.of()));
    }

    @Test
    void cannotEditOwnRole() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        assertForbidden("ROLE_OWN", () -> RoleAuthority.checkEdit(admin, role("ADMINISTRATOR"), Map.of()));
        RoleAuthority.checkEdit(callerWith("OWNER"), role("ADMINISTRATOR"), Map.of());
    }

    @Test
    void ownerRoleIsLocked() {
        OrgScope owner = callerWith("OWNER");
        RuleViolationException locked = assertThrows(RuleViolationException.class,
                () -> RoleAuthority.checkEdit(owner, role("OWNER"), role("OWNER").grants()));
        assertEquals("ROLE_OWNER_LOCKED", locked.getCode());
        OrganizationMembership worker = member("WORKER");
        assertEquals("ROLE_OWNER_LOCKED", assertThrows(RuleViolationException.class,
                () -> RoleAuthority.checkAssign(owner, worker, role("OWNER"))).getCode());
        assertEquals("ROLE_OWNER_LOCKED", assertThrows(RuleViolationException.class,
                () -> RoleAuthority.checkAssign(callerWith("ADMINISTRATOR"), ownerMembership(), role("WORKER")))
                .getCode());
        assertEquals("ROLE_OWNER_LOCKED", assertThrows(RuleViolationException.class,
                () -> RoleAuthority.checkInvite(owner, role("OWNER"))).getCode());
    }

    @Test
    void editChecksTheRoleAsItStandsAndTheNewSet() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        assertForbidden("ROLE_ABOVE_CALLER", () -> RoleAuthority.checkEdit(admin, role("FINANCE"), Map.of()));
        assertForbidden("ROLE_ABOVE_CALLER",
                () -> RoleAuthority.checkEdit(admin, role("WORKER"), Map.of(MANAGE_BUDGET, ORGANIZATION,
                        VIEW_BUDGET, ORGANIZATION)));
        RoleAuthority.checkEdit(admin, role("WORKER"), Map.of(VIEW_PRICES, ORGANIZATION));
        assertForbidden("ROLE_ABOVE_CALLER",
                () -> RoleAuthority.checkCreate(admin, Map.of(MANAGE_ORGANIZATION, ORGANIZATION)));
        RoleAuthority.checkCreate(admin, role("WORKER").grants());
    }

    @Test
    void editNeedsManageRoles() {
        OrgScope membersOnly = scope(member("WORKER"), Map.of(MANAGE_MEMBERS, ORGANIZATION));
        assertThrows(AccessDeniedException.class,
                () -> RoleAuthority.checkEdit(membersOnly, role("WORKER"), Map.of()));
        assertThrows(AccessDeniedException.class, () -> RoleAuthority.checkCreate(membersOnly, Map.of()));
    }

    @Test
    void cannotAssignSelf() {
        OrganizationMembership adminMembership = member("ADMINISTRATOR");
        OrgScope admin = scope(adminMembership, adminMembership.getRole().grants());
        assertForbidden("ROLE_OWN", () -> RoleAuthority.checkAssign(admin, adminMembership, role("WORKER")));
    }

    @Test
    void cannotReassignMemberWhoseCurrentRoleIsAboveCaller() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        OrganizationMembership finance = member("FINANCE");
        finance.suspend();
        assertForbidden("ROLE_ABOVE_CALLER", () -> RoleAuthority.checkAssign(admin, finance, role("WORKER")));
        OrganizationMembership worker = member("WORKER");
        assertForbidden("ROLE_ABOVE_CALLER", () -> RoleAuthority.checkAssign(admin, worker, role("FINANCE")));
        RoleAuthority.checkAssign(admin, worker, role("MEMBER"));
    }

    @Test
    void cannotInviteAboveCaller() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        assertForbidden("ROLE_ABOVE_CALLER", () -> RoleAuthority.checkInvite(admin, role("FINANCE")));
        RoleAuthority.checkInvite(admin, role("WORKER"));
        RoleAuthority.checkInvite(admin, role("ADMINISTRATOR"));
    }

    @Test
    void flagsMirrorTheChecks() {
        OrgScope admin = callerWith("ADMINISTRATOR");
        assertFalse(RoleAuthority.editable(admin, role("FINANCE")));
        assertFalse(RoleAuthority.assignable(admin, role("FINANCE")));
        assertTrue(RoleAuthority.editable(admin, role("WORKER")));
        assertTrue(RoleAuthority.assignable(admin, role("WORKER")));
        assertFalse(RoleAuthority.editable(admin, role("ADMINISTRATOR")));
        assertTrue(RoleAuthority.assignable(admin, role("ADMINISTRATOR")));
        assertFalse(RoleAuthority.editable(admin, role("OWNER")));
        assertFalse(RoleAuthority.assignable(admin, role("OWNER")));

        OrgScope owner = callerWith("OWNER");
        assertFalse(RoleAuthority.editable(owner, role("OWNER")));
        assertFalse(RoleAuthority.assignable(owner, role("OWNER")));
        assertTrue(RoleAuthority.editable(owner, role("FINANCE")));
        assertTrue(RoleAuthority.assignable(owner, role("FINANCE")));
    }

    @Test
    void flagsFollowThePermissionEachOperationNeeds() {
        OrganizationMembership membership = member("MEMBER");
        Map<PermissionAction, PermissionScope> workerGrants = role("WORKER").grants();
        Map<PermissionAction, PermissionScope> rolesOnly = new java.util.EnumMap<>(workerGrants);
        rolesOnly.put(MANAGE_ROLES, ORGANIZATION);
        assertTrue(RoleAuthority.editable(scope(membership, rolesOnly), role("WORKER")));
        assertFalse(RoleAuthority.assignable(scope(membership, rolesOnly), role("WORKER")));

        Map<PermissionAction, PermissionScope> membersOnly = new java.util.EnumMap<>(workerGrants);
        membersOnly.put(MANAGE_MEMBERS, ORGANIZATION);
        assertFalse(RoleAuthority.editable(scope(membership, membersOnly), role("WORKER")));
        assertTrue(RoleAuthority.assignable(scope(membership, membersOnly), role("WORKER")));
    }

    private void assertForbidden(String code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(ForbiddenRuleException.class, call).getCode());
    }

    private OrganizationMembership ownerMembership() {
        return organization.getMemberships().iterator().next();
    }

    private OrgScope callerWith(String roleCode) {
        OrganizationMembership membership = roleCode.equals("OWNER")
                ? ownerMembership()
                : member(roleCode);
        return scope(membership, membership.getRole().grants());
    }

    private OrgScope scope(OrganizationMembership membership, Map<PermissionAction, PermissionScope> grants) {
        return OrgScopeFixtures.org(orgId, membership, grants);
    }

    private OrganizationMembership member(String roleCode) {
        return new OrganizationMembership(organization,
                new UserAccount(roleCode.toLowerCase() + UUID.randomUUID() + "@acme.com", "hash"), role(roleCode));
    }

    private OrganizationRole role(String code) {
        return organization.getRoles().stream().filter(r -> r.getCode().equals(code)).findFirst().orElseThrow();
    }
}

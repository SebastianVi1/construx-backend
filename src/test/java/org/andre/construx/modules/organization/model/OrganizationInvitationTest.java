package org.andre.construx.modules.organization.model;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.project.model.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationInvitationTest {
    private Organization organization;
    private OrganizationMembership inviter;
    private OrganizationRole worker;
    private final Instant expires = Instant.now().plus(7, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() {
        organization = new Organization("Acme", new UserAccount("owner@acme.com", "hash"));
        inviter = organization.getMemberships().iterator().next();
        worker = organization.getRoles().stream().filter(r -> r.getCode().equals("WORKER")).findFirst().orElseThrow();
    }

    @Test
    void projectIsOptional() {
        var invitation = new OrganizationInvitation(organization, "Bob@Acme.com", "hash", worker, inviter, expires);
        assertNull(invitation.getProject());
        assertEquals("bob@acme.com", invitation.getEmail());
    }

    @Test
    void invitationCanTargetAProjectOfTheSameOrganization() {
        Project site = new Project(organization, "Tower");
        var invitation = new OrganizationInvitation(organization, "bob@acme.com", "hash", worker, inviter, expires, site);
        assertSame(site, invitation.getProject());
    }

    @Test
    void projectOfAnotherOrganizationIsRejected() {
        Organization other = new Organization("Other", new UserAccount("o@other.com", "hash"));
        Project foreign = new Project(other, "Foreign site");
        assertThrows(IllegalArgumentException.class,
                () -> new OrganizationInvitation(organization, "bob@acme.com", "hash", worker, inviter, expires, foreign));
    }

    @Test
    void acceptStillCreatesTheMembershipWithTheInvitedRole() {
        var invitation = new OrganizationInvitation(organization, "bob@acme.com", "hash", worker, inviter, expires);
        UserAccount bob = new UserAccount("bob@acme.com", "hash");
        OrganizationMembership membership = invitation.accept(bob, Instant.now());
        assertEquals("WORKER", membership.getRole().getCode());
        assertEquals(InvitationStatus.ACCEPTED, invitation.getStatus());
    }

    @Test
    void ownerRoleCannotBeInvited() {
        OrganizationRole owner = organization.getRoles().stream().filter(r -> r.getCode().equals("OWNER")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new OrganizationInvitation(organization, "bob@acme.com", "hash", owner, inviter, expires));
    }

    @Test
    void revokedOrExpiredInvitationCannotBeAccepted() {
        var revoked = new OrganizationInvitation(organization, "bob@acme.com", "h1", worker, inviter, expires);
        revoked.revoke();
        assertThrows(IllegalStateException.class, () -> revoked.accept(new UserAccount("bob@acme.com", "x"), Instant.now()));
        assertThrows(IllegalStateException.class, () -> revoked.revoke());
        var live = new OrganizationInvitation(organization, "bob@acme.com", "h2", worker, inviter, expires);
        assertThrows(IllegalStateException.class,
                () -> live.accept(new UserAccount("bob@acme.com", "x"), expires.plusSeconds(1)));
    }
}

package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.service.AccountService;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.OrganizationInvitation;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationInvitationRepository;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.access.SiteScopes;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class InvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationInvitationRepository invitations;
    private final OrganizationMembershipRepository memberships;
    private final OrganizationRoleRepository roles;
    private final SiteScopes sites;
    private final ProjectAccessService accesses;
    private final AccountService accounts;
    private final InvitationMailer mailer;
    private final Duration ttl;

    public InvitationService(OrganizationInvitationRepository invitations, OrganizationMembershipRepository memberships,
                             OrganizationRoleRepository roles, SiteScopes sites, ProjectAccessService accesses,
                             AccountService accounts, InvitationMailer mailer,
                             @Value("${app.invitations.ttl:7d}") Duration ttl) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.roles = roles;
        this.sites = sites;
        this.accesses = accesses;
        this.accounts = accounts;
        this.mailer = mailer;
        this.ttl = ttl;
    }

    public record InvitationData(UUID id, String email, String roleCode, String roleName, UUID projectId,
                                 InvitationStatus status, Instant expiresAt) {
        static InvitationData from(OrganizationInvitation invitation) {
            return new InvitationData(invitation.getId(), invitation.getEmail(), invitation.getRole().getCode(),
                    invitation.getRole().getName(),
                    invitation.getProject() == null ? null : invitation.getProject().getId(),
                    invitation.getStatus(), invitation.getExpiresAt());
        }
    }

    @Transactional
    public InvitationData create(OrgScope org, String email, String roleCode, UUID projectId) {
        Issued issued = issue(org, email, roleCode, projectId);
        OrganizationInvitation invitation = issued.invitation();
        String organizationName = invitation.getOrganization().getName();
        afterCommit(() -> mailer.send(invitation.getEmail(), organizationName, issued.rawToken()));
        return InvitationData.from(invitation);
    }

    /**
     * Adds an existing account to the organization as if it had been invited and accepted at once, so the same
     * checks run as for a real invitation. No email is sent. For trusted provisioning (the demo seed); no endpoint
     * exposes it.
     *
     * @return the new membership id
     */
    @Transactional
    public UUID enroll(OrgScope org, UUID inviteeUserId, String roleCode) {
        String email = accounts.account(inviteeUserId).getEmail();
        Issued issued = issue(org, email, roleCode, null);
        return accept(inviteeUserId, issued.rawToken()).membershipId();
    }

    private record Issued(OrganizationInvitation invitation, String rawToken) {}

    private Issued issue(OrgScope org, String email, String roleCode, UUID projectId) {
        OrganizationMembership inviter = org.membership();
        org.require(PermissionAction.MANAGE_MEMBERS);
        OrganizationRole role = roles.findByOrganizationIdAndCode(org.organizationId(), roleCode)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        Project project = null;
        if (projectId != null) {
            SiteScope site = sites.site(org, projectId);
            site.require(PermissionAction.MANAGE_PROJECT);
            project = site.site();
        }
        String normalized = UserAccount.normalizeEmail(email);
        if (memberships.existsByOrganizationIdAndUserAccountEmail(org.organizationId(), normalized)) {
            throw new IllegalStateException("That person is already a member");
        }
        String rawToken = newToken();
        OrganizationInvitation invitation = new OrganizationInvitation(inviter.getOrganization(), normalized,
                TokenService.sha256(rawToken), role, inviter, Instant.now().plus(ttl), project);
        invitations.findByOrganizationIdAndEmailAndStatus(org.organizationId(), normalized, InvitationStatus.PENDING)
                .forEach(OrganizationInvitation::revoke);
        invitations.save(invitation);
        return new Issued(invitation, rawToken);
    }

    public record AcceptedData(UUID organizationId, String organizationName, UUID membershipId,
                               String roleCode, String roleName, UUID projectId) {}

    /**
     * The token identifies the tenant, so every failure before the state checks is the same 404:
     * an unknown token and someone else's token are indistinguishable.
     */
    @Transactional
    public AcceptedData accept(UUID userId, String rawToken) {
        UserAccount user = accounts.account(userId);
        OrganizationInvitation invitation = invitations.findByTokenHashForUpdate(TokenService.sha256(rawToken))
                .filter(i -> i.getEmail().equals(user.getEmail()))
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        UUID organizationId = invitation.getOrganization().getId();
        if (memberships.findByOrganizationIdAndUserAccountId(organizationId, userId).isPresent()) {
            throw new IllegalStateException("You already belong to this organization");
        }
        OrganizationMembership membership = memberships.save(invitation.accept(user, Instant.now()));
        Project project = invitation.getProject();
        if (project != null) {
            accesses.grant(project, membership);
        }
        return new AcceptedData(organizationId, invitation.getOrganization().getName(), membership.getId(),
                invitation.getRole().getCode(), invitation.getRole().getName(),
                project == null ? null : project.getId());
    }

    @Transactional(readOnly = true)
    public List<InvitationData> list(OrgScope org, InvitationStatus status) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        return invitations.findByOrganizationAndStatus(org.organizationId(), status).stream()
                .map(InvitationData::from).toList();
    }

    @Transactional
    public void revoke(OrgScope org, UUID invitationId) {
        org.require(PermissionAction.MANAGE_MEMBERS);
        invitations.findByIdAndOrganizationId(invitationId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"))
                .revoke();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

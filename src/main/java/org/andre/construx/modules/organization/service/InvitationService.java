package org.andre.construx.modules.organization.service;

import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.service.TokenService;
import org.andre.construx.modules.organization.model.InvitationStatus;
import org.andre.construx.modules.organization.model.MembershipStatus;
import org.andre.construx.modules.organization.model.OrganizationInvitation;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.OrganizationRole;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationInvitationRepository;
import org.andre.construx.modules.organization.repository.OrganizationMembershipRepository;
import org.andre.construx.modules.organization.repository.OrganizationRoleRepository;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
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
    private final ProjectRepository projects;
    private final DomainAuthorization authorization;
    private final InvitationMailer mailer;
    private final Duration ttl;

    public InvitationService(OrganizationInvitationRepository invitations, OrganizationMembershipRepository memberships,
                             OrganizationRoleRepository roles, ProjectRepository projects,
                             DomainAuthorization authorization, InvitationMailer mailer,
                             @Value("${app.invitations.ttl:7d}") Duration ttl) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.roles = roles;
        this.projects = projects;
        this.authorization = authorization;
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
    public InvitationData create(UUID userId, UUID organizationId, String email, String roleCode, UUID projectId) {
        OrganizationMembership inviter = requireActiveMember(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        OrganizationRole role = roles.findByOrganizationIdAndCode(organizationId, roleCode)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        Project project = null;
        if (projectId != null) {
            project = projects.findByIdAndOrganizationId(projectId, organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
            authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PROJECT);
        }
        String normalized = UserAccount.normalizeEmail(email);
        if (memberships.existsByOrganizationIdAndUserAccountEmail(organizationId, normalized)) {
            throw new IllegalStateException("That person is already a member");
        }
        String rawToken = newToken();
        OrganizationInvitation invitation = new OrganizationInvitation(inviter.getOrganization(), normalized,
                TokenService.sha256(rawToken), role, inviter, Instant.now().plus(ttl), project);
        invitations.findByOrganizationIdAndEmailAndStatus(organizationId, normalized, InvitationStatus.PENDING)
                .forEach(OrganizationInvitation::revoke);
        invitations.save(invitation);
        String organizationName = inviter.getOrganization().getName();
        afterCommit(() -> mailer.send(normalized, organizationName, rawToken));
        return InvitationData.from(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationData> list(UUID userId, UUID organizationId, InvitationStatus status) {
        requireActiveMember(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        return invitations.findByOrganizationAndStatus(organizationId, status).stream()
                .map(InvitationData::from).toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID organizationId, UUID invitationId) {
        requireActiveMember(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_MEMBERS);
        invitations.findByIdAndOrganizationId(invitationId, organizationId)
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

    /** Non-members get the same 404 as a missing organization, so identifiers cannot be enumerated. */
    private OrganizationMembership requireActiveMember(UUID userId, UUID organizationId) {
        return memberships.findByOrganizationIdAndUserAccountId(organizationId, userId)
                .filter(membership -> membership.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    }
}

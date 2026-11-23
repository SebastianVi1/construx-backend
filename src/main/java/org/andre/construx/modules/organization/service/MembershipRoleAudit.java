package org.andre.construx.modules.organization.service;

import org.andre.construx.shared.audit.AuditService;

import java.util.UUID;

/**
 * Audit of a member moving between roles (contract roles §7): entity {@code ORGANIZATION_MEMBERSHIP}, action
 * {@code ROLE_CHANGE}, before/after {@code { roleCode }}. Used by assignment and by a role delete that moves its
 * members to a replacement.
 */
public final class MembershipRoleAudit {
    public static final String ENTITY = "ORGANIZATION_MEMBERSHIP";
    public static final String ROLE_CHANGE = "ROLE_CHANGE";

    /** The before/after state of a {@code ROLE_CHANGE} event. */
    public record State(String roleCode) {}

    private MembershipRoleAudit() {
    }

    public static void record(AuditService audit, UUID membershipId, String fromCode, String toCode,
                              UUID actorMembershipId) {
        audit.record(ENTITY, membershipId, ROLE_CHANGE, actorMembershipId, new State(fromCode), new State(toCode));
    }
}

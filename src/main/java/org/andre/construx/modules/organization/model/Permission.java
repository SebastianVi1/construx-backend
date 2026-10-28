package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "role_permission", uniqueConstraints =
        @UniqueConstraint(name = "uk_role_action", columnNames = {"role_id", "action"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private OrganizationRole role;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PermissionAction action;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PermissionScope scope;

    Permission(OrganizationRole role, PermissionAction action, PermissionScope scope) {
        this.role = DomainValidation.required(role, "Role is required");
        this.action = DomainValidation.required(action, "Action is required");
        changeScope(scope);
    }

    void changeScope(PermissionScope scope) {
        this.scope = DomainValidation.required(scope, "Scope is required");
        validate(action, scope);
    }

    /** Rejects action and scope pairs that make no sense, e.g. a project-scoped role management. */
    static void validate(PermissionAction action, PermissionScope scope) {
        if ((action == PermissionAction.MANAGE_ORGANIZATION
                || action == PermissionAction.MANAGE_ROLES
                || action == PermissionAction.MANAGE_MEMBERS)
                && scope != PermissionScope.ORGANIZATION) {
            throw new IllegalArgumentException("Organization administration requires organization scope");
        }
        if ((action == PermissionAction.VIEW_PROJECT
                || action == PermissionAction.MANAGE_PROJECT
                || action == PermissionAction.VIEW_GOAL
                || action == PermissionAction.MANAGE_GOAL)
                && scope == PermissionScope.OWN) {
            throw new IllegalArgumentException("Projects and goals have no individual owner");
        }
        if (action == PermissionAction.ASSIGN_ACTIVITY && scope == PermissionScope.OWN) {
            throw new IllegalArgumentException("Activity assignment requires project or organization scope");
        }
        if ((action == PermissionAction.VIEW_PRICES || action == PermissionAction.MANAGE_PRICES)
                && scope != PermissionScope.ORGANIZATION) {
            throw new IllegalArgumentException("Material prices belong to the whole organization");
        }
        if ((action == PermissionAction.VIEW_BUDGET || action == PermissionAction.MANAGE_BUDGET)
                && scope != PermissionScope.ORGANIZATION) {
            throw new IllegalArgumentException("Site budgets are an organization-wide finance permission");
        }
    }
}

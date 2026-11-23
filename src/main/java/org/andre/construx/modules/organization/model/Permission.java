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

    /** Rejects action and scope pairs the catalog does not allow, e.g. a project-scoped role management. */
    static void validate(PermissionAction action, PermissionScope scope) {
        PermissionRules.checkScope(action, scope);
    }
}

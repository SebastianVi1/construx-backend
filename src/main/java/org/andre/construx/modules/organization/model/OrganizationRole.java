package org.andre.construx.modules.organization.model;

import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organization_role", uniqueConstraints = {
        @UniqueConstraint(name = "uk_role_org_code", columnNames = {"organization_id", "code"}),
        @UniqueConstraint(name = "uk_role_org_name", columnNames = {"organization_id", "name"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationRole {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotBlank @Size(max = 64)
    @Column(nullable = false, length = 64)
    private String code;

    @NotBlank @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean systemRole;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Permission> permissions = new HashSet<>();

    public OrganizationRole(Organization organization, String code, String name) {
        this.organization = DomainValidation.required(organization, "Organization is required");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}") || "OWNER".equals(code)) {
            throw new IllegalArgumentException("Invalid or reserved role code");
        }
        this.code = code;
        rename(name);
    }

    public static OrganizationRole owner(Organization organization) {
        OrganizationRole role = new OrganizationRole();
        role.organization = DomainValidation.required(organization, "Organization is required");
        role.code = "OWNER";
        role.name = "Propietario";
        role.systemRole = true;
        for (PermissionAction action : PermissionAction.values()) {
            role.permissions.add(new Permission(role, action, PermissionScope.ORGANIZATION));
        }
        return role;
    }

    public void rename(String name) {
        if (systemRole) {
            throw new IllegalStateException("System role cannot be renamed");
        }
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("Role name must have 1 to 100 characters");
        }
        this.name = name.strip();
    }

    public void grant(PermissionAction action, PermissionScope scope) {
        if (systemRole) {
            throw new IllegalStateException("System role permissions cannot be changed");
        }
        for (Permission permission : permissions) {
            if (permission.getAction() == action) {
                permission.changeScope(scope);
                return;
            }
        }
        permissions.add(new Permission(this, action, scope));
    }

    public void revoke(PermissionAction action) {
        if (systemRole) {
            throw new IllegalStateException("System role permissions cannot be changed");
        }
        permissions.removeIf(permission -> permission.getAction() == action);
    }

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }
}

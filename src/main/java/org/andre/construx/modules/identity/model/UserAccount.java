package org.andre.construx.modules.identity.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.organization.model.OrganizationMembership;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user-account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Email @NotBlank @Size(max = 254)
    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @NotBlank
    @Column(name = "password", nullable = false)
    private String passwordHash;

    @NotNull @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @OneToMany(mappedBy = "userAccount")
    private Set<OrganizationMembership> organizationMemberships = new HashSet<>();

    public UserAccount(String email, String passwordHash) {
        this.email = normalizeEmail(email);
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash is required");
        }
        this.passwordHash = passwordHash;
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254) {
            throw new IllegalArgumentException("Email is too long");
        }
        return normalized;
    }

    public boolean sameAs(UserAccount other) {
        return this == other || (other != null && id != null && id.equals(other.getId()));
    }
}

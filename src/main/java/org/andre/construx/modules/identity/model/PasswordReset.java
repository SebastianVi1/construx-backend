package org.andre.construx.modules.identity.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Single-use password reset token whose plaintext exists only in the recovery email. */
@Entity
@Table(name = "password_reset",
        indexes = @Index(name = "ix_password_reset_user", columnList = "user_account_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class PasswordReset {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false)
    private Instant createdAt;

    public PasswordReset(UserAccount user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean usableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void use(Instant now) {
        usedAt = now;
    }
}

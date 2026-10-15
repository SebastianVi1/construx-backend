package org.andre.construx.modules.identity.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One revocable browser session; only a hash of its refresh secret is persisted. */
@Entity
@Table(name = "auth_session")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AuthSession {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 64)
    private String refreshHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    public AuthSession(UserAccount user, String refreshHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.refreshHash = refreshHash;
        this.expiresAt = expiresAt;
    }

    public boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void rotate(String refreshHash) {
        this.refreshHash = refreshHash;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}

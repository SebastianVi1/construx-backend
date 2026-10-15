package org.andre.construx.modules.identity.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Google subject is stable; an email address is not an identity key. */
@Entity
@Table(name = "google_identity", uniqueConstraints = @UniqueConstraint(columnNames = "subject"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class GoogleIdentity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    private UserAccount user;

    public GoogleIdentity(String subject, UserAccount user) {
        this.subject = subject;
        this.user = user;
    }
}

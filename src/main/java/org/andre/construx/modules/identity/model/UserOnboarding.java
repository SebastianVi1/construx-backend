package org.andre.construx.modules.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

/** The user's answers to the first-run questions. Independent of any organization (ADR 0002). */
@Entity
@Table(name = "user_onboarding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOnboarding {
    @Id
    private UUID id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OnboardingIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", length = 30)
    private BusinessType businessType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserOnboarding(UserAccount account, OnboardingIntent intent, BusinessType businessType) {
        this.account = account;
        answer(intent, businessType);
    }

    public void answer(OnboardingIntent intent, BusinessType businessType) {
        if (intent == null) {
            throw new IllegalArgumentException("Intent is required");
        }
        this.intent = intent;
        this.businessType = businessType;
        this.updatedAt = Instant.now();
    }
}

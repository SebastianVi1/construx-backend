package org.andre.construx.modules.identity.service;

import org.andre.construx.modules.identity.model.BusinessType;
import org.andre.construx.modules.identity.model.OnboardingIntent;
import org.andre.construx.modules.identity.model.UserAccount;
import org.andre.construx.modules.identity.model.UserOnboarding;
import org.andre.construx.modules.identity.repository.UserAccountRepository;
import org.andre.construx.modules.identity.repository.UserOnboardingRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OnboardingService {
    private final UserAccountRepository users;
    private final UserOnboardingRepository onboardings;

    public OnboardingService(UserAccountRepository users, UserOnboardingRepository onboardings) {
        this.users = users;
        this.onboardings = onboardings;
    }

    public record OnboardingData(OnboardingIntent intent, BusinessType businessType, Instant updatedAt) {
        static OnboardingData from(UserOnboarding onboarding) {
            return new OnboardingData(onboarding.getIntent(), onboarding.getBusinessType(), onboarding.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public OnboardingData get(UUID accountId) {
        requireAccount(accountId);
        return onboardings.findById(accountId).map(OnboardingData::from)
                .orElse(new OnboardingData(null, null, null));
    }

    @Transactional
    public OnboardingData answer(UUID accountId, OnboardingIntent intent, BusinessType businessType) {
        UserAccount account = requireAccount(accountId);
        UserOnboarding onboarding = onboardings.findById(accountId).orElse(null);
        if (onboarding == null) {
            onboarding = new UserOnboarding(account, intent, businessType);
        } else {
            onboarding.answer(intent, businessType);
        }
        return OnboardingData.from(onboardings.save(onboarding));
    }

    private UserAccount requireAccount(UUID id) {
        return users.findById(id).orElseThrow(() -> new BadCredentialsException("Account unavailable"));
    }
}

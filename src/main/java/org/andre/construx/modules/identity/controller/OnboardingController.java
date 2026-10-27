package org.andre.construx.modules.identity.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.identity.model.BusinessType;
import org.andre.construx.modules.identity.model.OnboardingIntent;
import org.andre.construx.modules.identity.service.OnboardingService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Self-service first-run answers; no caller-supplied account identifier. */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {
    private final OnboardingService onboarding;

    public OnboardingController(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    public record OnboardingUpdate(@NotNull OnboardingIntent intent, BusinessType businessType) {}

    public record OnboardingResponse(OnboardingIntent intent, BusinessType businessType, Instant updatedAt) {
        static OnboardingResponse from(OnboardingService.OnboardingData data) {
            return new OnboardingResponse(data.intent(), data.businessType(), data.updatedAt());
        }
    }

    @GetMapping
    public OnboardingResponse get(Authentication authentication) {
        return OnboardingResponse.from(onboarding.get(UUID.fromString(authentication.getName())));
    }

    @PutMapping
    public OnboardingResponse answer(@Valid @RequestBody OnboardingUpdate body, Authentication authentication) {
        return OnboardingResponse.from(onboarding.answer(UUID.fromString(authentication.getName()),
                body.intent(), body.businessType()));
    }
}

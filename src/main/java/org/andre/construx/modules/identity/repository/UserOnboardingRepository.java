package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.UserOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserOnboardingRepository extends JpaRepository<UserOnboarding, UUID> {
}

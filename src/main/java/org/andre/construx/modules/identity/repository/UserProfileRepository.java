package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}

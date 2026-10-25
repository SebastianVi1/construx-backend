package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.EmailVerification;
import org.andre.construx.modules.identity.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** Marks every unused verification link of the user as used so only the newest one works. */
    @Modifying
    @Query("update EmailVerification v set v.usedAt = :now where v.user = :user and v.usedAt is null")
    void invalidateOpenFor(@Param("user") UserAccount user, @Param("now") Instant now);
}

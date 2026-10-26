package org.andre.construx.modules.identity.repository;

import org.andre.construx.modules.identity.model.PasswordReset;
import org.andre.construx.modules.identity.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /** Atomically marks one open token as used; returns 0 when it was already used or has expired. */
    @Modifying
    @Query("update PasswordReset r set r.usedAt = :now where r.id = :id and r.usedAt is null and r.expiresAt > :now")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /** Marks every unused reset link of the user as used so only the newest one works. */
    @Modifying
    @Query("update PasswordReset r set r.usedAt = :now where r.user = :user and r.usedAt is null")
    void invalidateOpenFor(@Param("user") UserAccount user, @Param("now") Instant now);

    @Query("select count(r) > 0 from PasswordReset r where r.user = :user and r.usedAt is null "
            + "and r.expiresAt > :now and r.createdAt > :cutoff")
    boolean existsOpenCreatedAfter(@Param("user") UserAccount user, @Param("now") Instant now,
            @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from PasswordReset r where r.expiresAt < :cutoff or r.usedAt < :cutoff")
    int deleteDeadBefore(@Param("cutoff") Instant cutoff);
}

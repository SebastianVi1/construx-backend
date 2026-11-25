package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.WrongCodes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface WrongCodesRepository extends JpaRepository<WrongCodes, String> {

    /** Rows whose window started before {@code windowCutoff} and that block nothing at {@code now}. */
    @Modifying
    @Query("delete from WrongCodes w where w.windowStart < :windowCutoff"
            + " and (w.blockedUntil is null or w.blockedUntil <= :now)")
    int deleteExpired(@Param("windowCutoff") Instant windowCutoff, @Param("now") Instant now);
}

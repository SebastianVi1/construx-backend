package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.UnlinkedReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UnlinkedReplyRepository extends JpaRepository<UnlinkedReply, String> {

    @Modifying
    @Query("delete from UnlinkedReply r where r.repliedAt < :cutoff")
    int deleteRepliedBefore(@Param("cutoff") Instant cutoff);
}

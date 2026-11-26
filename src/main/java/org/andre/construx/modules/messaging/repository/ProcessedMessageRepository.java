package org.andre.construx.modules.messaging.repository;

import org.andre.construx.modules.messaging.model.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    /** Records a message id; 1 the first time, 0 when it was already there (atomic, also across instances). */
    @Transactional
    @Modifying
    @Query(value = "insert into whatsapp_processed_message (message_id, wa_id, received_at) "
            + "values (:messageId, :waId, :receivedAt) on conflict do nothing", nativeQuery = true)
    int record(@Param("messageId") String messageId, @Param("waId") String waId,
            @Param("receivedAt") Instant receivedAt);

    @Modifying
    @Query("delete from ProcessedMessage m where m.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}

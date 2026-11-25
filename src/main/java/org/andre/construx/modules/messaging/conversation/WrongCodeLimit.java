package org.andre.construx.modules.messaging.conversation;

import org.andre.construx.modules.messaging.model.WrongCodes;
import org.andre.construx.modules.messaging.repository.WrongCodesRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Contract §4.1: 10 wrong link codes from one number in an hour, and the number is ignored for an hour, so codes
 * cannot be guessed. Kept in the database, so it holds across restarts and instances.
 */
@Component
class WrongCodeLimit {
    private final WrongCodesRepository wrongCodes;
    private final Clock clock;

    WrongCodeLimit(WrongCodesRepository wrongCodes, Clock clock) {
        this.wrongCodes = wrongCodes;
        this.clock = clock;
    }

    /** Whether every message from the number is ignored right now. */
    @Transactional(readOnly = true)
    boolean blocks(String waId) {
        Instant now = clock.instant();
        return wrongCodes.findById(waId).filter(codes -> codes.blocksAt(now)).isPresent();
    }

    @Transactional
    void countWrongCode(String waId) {
        Instant now = clock.instant();
        wrongCodes.findById(waId).ifPresentOrElse(codes -> codes.countAt(now),
                () -> wrongCodes.save(new WrongCodes(waId, now)));
    }
}

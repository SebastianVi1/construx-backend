package org.andre.construx.modules.messaging.inbound;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contract §4.3: up to 30 inbound messages per minute per sender. The 31st starts a 5-minute block in which every
 * message from that sender is dropped without a reply. Fixed one-minute windows; state is in memory, so per instance.
 */
@Component
class SenderRateLimiter {
    static final int MAX_PER_WINDOW = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration BLOCK = Duration.ofMinutes(5);
    private static final int PRUNE_THRESHOLD = 10_000;

    private record State(Instant windowStart, int count, Instant blockedUntil) {}

    private final Map<String, State> senders = new ConcurrentHashMap<>();
    private final Clock clock;

    SenderRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts the message and says whether it may be processed. */
    boolean allow(String waId) {
        Instant now = clock.instant();
        if (senders.size() > PRUNE_THRESHOLD) {
            senders.values().removeIf(state -> isStale(state, now));
        }
        State state = senders.compute(waId, (id, current) -> next(current, now));
        return state.blockedUntil() == null;
    }

    private static State next(State current, Instant now) {
        if (current != null && current.blockedUntil() != null && now.isBefore(current.blockedUntil())) {
            return current;
        }
        if (current == null || current.blockedUntil() != null || !now.isBefore(current.windowStart().plus(WINDOW))) {
            return new State(now, 1, null);
        }
        int count = current.count() + 1;
        return count > MAX_PER_WINDOW ? new State(current.windowStart(), count, now.plus(BLOCK))
                : new State(current.windowStart(), count, null);
    }

    private static boolean isStale(State state, Instant now) {
        Instant end = state.blockedUntil() != null ? state.blockedUntil() : state.windowStart().plus(WINDOW);
        return !now.isBefore(end);
    }
}

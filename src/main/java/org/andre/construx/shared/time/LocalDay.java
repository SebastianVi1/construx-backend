package org.andre.construx.shared.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The calendar day as the business sees it: the date in the one configured time zone ({@code app.time-zone}),
 * not the server's, which runs in UTC and would call a Mexican evening "tomorrow". Ask this instead of
 * {@code LocalDate.now()} wherever "today" decides something.
 */
@Component
public class LocalDay {

    private final ZoneId zone;
    private final Clock clock;

    /** An unknown zone name is refused here, so a typo stops startup instead of silently becoming UTC. */
    public LocalDay(@Value("${app.time-zone}") String timeZone, Clock clock) {
        try {
            this.zone = ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            throw new IllegalStateException("app.time-zone (APP_TIME_ZONE) is not a known time zone: '"
                    + timeZone + "'. Use a region name such as America/Mexico_City.", e);
        }
        this.clock = clock;
    }

    public LocalDate today() {
        return of(clock.instant());
    }

    /** The day on which {@code moment} falls in the configured time zone. */
    public LocalDate of(Instant moment) {
        return moment.atZone(zone).toLocalDate();
    }

    public ZoneId zone() {
        return zone;
    }
}

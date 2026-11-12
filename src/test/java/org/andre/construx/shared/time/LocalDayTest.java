package org.andre.construx.shared.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Today" is the calendar day of the configured time zone, not of the server. Mexico City is UTC-6 all year. */
class LocalDayTest {

    private static LocalDay at(String zone, String instant) {
        return new LocalDay(zone, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void lateEveningIsStillTheSameLocalDay() {
        // 23:30 on 10 March in Mexico City, already 11 March for a UTC server
        assertEquals(LocalDate.of(2026, 3, 10), at("America/Mexico_City", "2026-03-11T05:30:00Z").today());
    }

    @Test
    void shortlyAfterMidnightIsTheNextLocalDay() {
        // 00:30 on 11 March in Mexico City
        assertEquals(LocalDate.of(2026, 3, 11), at("America/Mexico_City", "2026-03-11T06:30:00Z").today());
    }

    @Test
    void aMomentFallsOnItsLocalDayWhateverTheClockSays() {
        LocalDay day = at("America/Mexico_City", "2026-07-01T12:00:00Z");

        assertEquals(LocalDate.of(2026, 3, 10), day.of(Instant.parse("2026-03-11T05:30:00Z")));
        assertEquals(LocalDate.of(2026, 3, 11), day.of(Instant.parse("2026-03-11T06:30:00Z")));
    }

    @Test
    void theDayFollowsTheConfiguredZone() {
        // 08:30 on 11 March in Tokyo while it is still 10 March in Mexico City
        assertEquals(LocalDate.of(2026, 3, 11), at("Asia/Tokyo", "2026-03-10T23:30:00Z").today());
    }

    @Test
    void anUnknownTimeZoneIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> at("America/Mexico", "2026-03-11T05:30:00Z"));

        assertTrue(e.getMessage().contains("America/Mexico"), e.getMessage());
        assertTrue(e.getMessage().contains("app.time-zone"), e.getMessage());
    }

    @Test
    void aBlankTimeZoneIsRefused() {
        assertThrows(IllegalStateException.class, () -> at(" ", "2026-03-11T05:30:00Z"));
    }
}

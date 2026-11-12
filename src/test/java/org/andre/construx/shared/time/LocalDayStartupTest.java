package org.andre.construx.shared.time;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The time zone is read once at startup: a bad name stops the application instead of falling back. */
class LocalDayStartupTest {

    private final ApplicationContextRunner app = new ApplicationContextRunner()
            .withUserConfiguration(TimeConfig.class, LocalDay.class);

    @Test
    void startsInTheConfiguredZone() {
        app.withPropertyValues("app.time-zone=Europe/Madrid").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LocalDay.class).zone()).isEqualTo(ZoneId.of("Europe/Madrid"));
        });
    }

    @Test
    void anUnknownZoneStopsStartup() {
        app.withPropertyValues("app.time-zone=Mars/Olympus").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(java.time.DateTimeException.class)
                    .hasStackTraceContaining("app.time-zone")
                    .hasStackTraceContaining("Mars/Olympus");
        });
    }
}

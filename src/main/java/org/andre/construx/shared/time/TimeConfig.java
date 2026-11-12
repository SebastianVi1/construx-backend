package org.andre.construx.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** The one clock to inject where time must be controllable in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

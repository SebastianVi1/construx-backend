package org.andre.construx.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** Async executor for long-running background tasks (e.g. PDF rendering for floor plans). */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "floorPlanExecutor")
    public Executor floorPlanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("floor-plan-");
        executor.initialize();
        return executor;
    }
}

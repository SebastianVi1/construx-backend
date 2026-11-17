package org.andre.construx.modules.pricing.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** One import at a time per instance (docs/pricing.md §4.2); the database allows one active job per organization. */
@Configuration
class CatalogImportConfig {

    @Bean(name = "catalogImportExecutor")
    Executor catalogImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("catalog-import-");
        executor.initialize();
        return executor;
    }
}

package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.ImportStatus;
import org.andre.construx.modules.pricing.repository.CatalogImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Cancels a {@code VALIDATED} Catalog import nobody confirmed within 24 hours of its dry run, so it stops blocking
 * new imports (docs/pricing.md §1.11). Each job is locked and re-checked, so a confirm racing the sweep wins or
 * loses cleanly.
 */
@Component
public class StaleImportCanceller {
    static final Duration VALIDATED_TTL = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(StaleImportCanceller.class);

    private final CatalogImportRepository jobs;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StaleImportCanceller(CatalogImportRepository jobs, PlatformTransactionManager txm, Clock clock) {
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT15M")
    public void cancelStale() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(VALIDATED_TTL);
        int cancelled = 0;
        for (UUID id : jobs.findValidatedBefore(cutoff)) {
            Boolean done = tx.execute(s -> jobs.lock(id)
                    .filter(job -> job.getStatus() == ImportStatus.VALIDATED && job.getValidatedAt().isBefore(cutoff))
                    .map(job -> {
                        job.cancel(now);
                        return true;
                    }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("Cancelled {} catalog imports left unconfirmed for {}", cancelled, VALIDATED_TTL);
        }
    }
}

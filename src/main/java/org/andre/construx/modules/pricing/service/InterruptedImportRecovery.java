package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.ImportStatus;
import org.andre.construx.modules.pricing.repository.CatalogImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * On start, fails the Catalog imports a worker was running when the application stopped ({@code PENDING},
 * {@code VALIDATING}, {@code IMPORTING}) with {@code INTERRUPTED}, which frees the organization's active slot
 * (docs/pricing.md §4.7). The workers run inside this process, so after a restart nothing would ever finish them.
 * {@code VALIDATED} jobs wait for the user, not for a worker, and are left to {@link StaleImportCanceller}.
 */
@Component
public class InterruptedImportRecovery {
    static final String CODE = "INTERRUPTED";
    static final String MESSAGE =
            "la importación se interrumpió porque el servidor se reinició; vuelve a subir el archivo";
    private static final Logger log = LoggerFactory.getLogger(InterruptedImportRecovery.class);

    private final CatalogImportRepository jobs;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InterruptedImportRecovery(CatalogImportRepository jobs, PlatformTransactionManager txm, Clock clock) {
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int failed = 0;
        for (UUID id : jobs.findRunning()) {
            Boolean done = tx.execute(s -> jobs.lock(id)
                    .filter(job -> job.getStatus().isActive() && job.getStatus() != ImportStatus.VALIDATED)
                    .map(job -> {
                        job.fail(CODE, MESSAGE, null, null, null, now);
                        return true;
                    }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("Marked {} catalog imports interrupted by a restart as failed", failed);
        }
    }
}

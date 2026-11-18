package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.importing.FasarFile;
import org.andre.construx.modules.pricing.importing.ImportFailedException;
import org.andre.construx.modules.pricing.importing.ImportPlan;
import org.andre.construx.modules.pricing.importing.PresupuestoFile;
import org.andre.construx.modules.pricing.model.CatalogImport;
import org.andre.construx.modules.pricing.model.ImportStatus;
import org.andre.construx.modules.pricing.repository.CatalogImportRepository;
import org.andre.construx.shared.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Runs Catalog imports off the request thread on the dedicated single-thread executor (docs/pricing.md §4.2):
 * validation (the dry run) and, after confirm, the write in one transaction. State changes lock the job row, so a
 * cancel and the worker never both win.
 */
@Component
public class CatalogImportWorker {
    private static final Logger log = LoggerFactory.getLogger(CatalogImportWorker.class);

    private final Executor executor;
    private final TransactionTemplate tx;
    private final CatalogImportRepository jobs;
    private final ObjectStorage storage;
    private final CatalogImportStore store;
    private final DerivedCostService derivedCosts;
    private final ImportedFasarFields fasarFields;
    private final FasarService fasar;
    private final ObjectMapper json;

    public CatalogImportWorker(@Qualifier("catalogImportExecutor") Executor executor, PlatformTransactionManager txm,
                               CatalogImportRepository jobs, ObjectStorage storage, CatalogImportStore store,
                               DerivedCostService derivedCosts, ImportedFasarFields fasarFields, FasarService fasar,
                               ObjectMapper json) {
        this.executor = executor;
        this.tx = new TransactionTemplate(txm);
        this.jobs = jobs;
        this.storage = storage;
        this.store = store;
        this.derivedCosts = derivedCosts;
        this.fasarFields = fasarFields;
        this.fasar = fasar;
        this.json = json;
    }

    /** Validation starts once the transaction that created the job commits. */
    void scheduleValidation(UUID importId) {
        afterCommit(() -> validate(importId));
    }

    /** The write starts once the confirm commits. */
    void scheduleWrite(UUID importId) {
        afterCommit(() -> write(importId));
    }

    /** The sha-256 of a stored object, streamed. */
    String sha256(String key) {
        MessageDigest digest = sha();
        try (InputStream in = new DigestInputStream(storage.open(key), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the uploaded workbook", ex);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    void validate(UUID importId) {
        CatalogImport job = tx.execute(s -> {
            CatalogImport locked = jobs.lock(importId).orElse(null);
            if (locked == null || locked.getStatus() != ImportStatus.PENDING) {
                return null;
            }
            locked.startValidating();
            return locked;
        });
        if (job == null) {
            return;
        }
        Path file = null;
        Path fasarPath = null;
        try {
            file = download(job.getFileKey(), null);
            PresupuestoFile workbook = PresupuestoFile.read(file.toFile());
            fasarPath = job.getFasarFileKey() == null ? null : download(job.getFasarFileKey(), null);
            FasarFile fasarFile = fasarPath == null ? null : FasarFile.read(fasarPath.toFile());
            String summary = tx.execute(s -> json.writeValueAsString(plan(job.getOrganizationId(), workbook,
                    fasarFile).summary));
            transition(importId, j -> j.validated(summary, now()));
        } catch (ImportFailedException ex) {
            failed(importId, ex.getCode(), ex.getMessage(), ex.getSheet(), ex.getRow(), ex.getColumn());
        } catch (RuntimeException ex) {
            log.error("Catalog import {} could not be validated", importId, ex);
            failed(importId, "WORKBOOK_UNREADABLE", "No se pudo leer el libro de Excel.", null, null, null);
        } finally {
            delete(file);
            delete(fasarPath);
        }
    }

    void write(UUID importId) {
        CatalogImport job = tx.execute(s -> jobs.findById(importId)
                .filter(j -> j.getStatus() == ImportStatus.IMPORTING).orElse(null));
        if (job == null) {
            return;
        }
        Path file = null;
        Path fasarPath = null;
        try {
            file = download(job.getFileKey(), job.getFileSha256());
            PresupuestoFile workbook = PresupuestoFile.read(file.toFile());
            fasarPath = job.getFasarFileKey() == null ? null
                    : download(job.getFasarFileKey(), job.getFasarFileSha256());
            FasarFile fasarFile = fasarPath == null ? null : FasarFile.read(fasarPath.toFile());
            tx.executeWithoutResult(s -> {
                CatalogImport locked = jobs.lock(importId).orElseThrow();
                UUID organizationId = locked.getOrganizationId();
                UUID membershipId = locked.getLicenceAcceptedByMembershipId();
                ImportPlan plan = plan(organizationId, workbook, fasarFile);
                Instant at = now();
                store.write(organizationId, importId, plan, at);
                if (fasarFile != null) {
                    fasar.replaceFromFile(organizationId, fasarFile.year(), fasarFile.minimumWage(),
                            fasarFile.parameters(), at, membershipId);
                } else {
                    fasarFields.createPartialIfAbsent(organizationId, plan.generalFields(), at);
                }
                fasar.recomputeLaborCategories(organizationId, at, membershipId);
                derivedCosts.recompute(organizationId, at);
                store.finish(organizationId, importId);
                locked.refreshSummary(json.writeValueAsString(plan.summary));
                locked.done(at);
            });
        } catch (ImportFailedException ex) {
            failed(importId, ex.getCode(), ex.getMessage(), ex.getSheet(), ex.getRow(), ex.getColumn());
        } catch (RuntimeException ex) {
            log.error("Catalog import {} could not be written", importId, ex);
            failed(importId, "IMPORT_WRITE_FAILED",
                    "No se pudo escribir el catálogo; no se guardó ningún cambio.", null, null, null);
        } finally {
            delete(file);
            delete(fasarPath);
        }
    }

    /**
     * The plan against the Catalog as it is now, with the FASAR workbook or, without one, the organization's
     * complete FASAR set if it has one (§5.5).
     */
    private ImportPlan plan(UUID organizationId, PresupuestoFile workbook, FasarFile fasarFile) {
        return ImportPlan.of(workbook, store.snapshot(organizationId), fasarFile,
                fasarFile != null ? null : fasar.calculator(organizationId).orElse(null));
    }

    /**
     * Copies the object to a temporary file; with {@code expectedSha256} the bytes must still hash to it
     * ({@code FILE_CHANGED}).
     */
    private Path download(String key, String expectedSha256) {
        try {
            Path file = Files.createTempFile("catalog-import-", ".xlsx");
            MessageDigest digest = sha();
            try (InputStream in = new DigestInputStream(storage.open(key), digest)) {
                Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.equals(actual)) {
                delete(file);
                throw new ImportFailedException("FILE_CHANGED", null, null, null,
                        "el archivo cambió desde que se validó; vuelve a subirlo");
            }
            return file;
        } catch (IOException ex) {
            throw new ImportFailedException("WORKBOOK_UNREADABLE", null, null, null,
                    "no se pudo leer el archivo subido");
        }
    }

    private void failed(UUID importId, String code, String message, String sheet, Integer row, String column) {
        try {
            transition(importId, j -> j.fail(code, message, sheet, row, column, now()));
        } catch (RuntimeException ex) {
            log.error("Catalog import {} could not be marked failed", importId, ex);
        }
    }

    private void transition(UUID importId, java.util.function.Consumer<CatalogImport> change) {
        tx.executeWithoutResult(s -> jobs.lock(importId).ifPresent(job -> {
            if (job.getStatus().isActive()) {
                change.accept(job);
            }
        }));
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    private static void delete(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a temporary file
            }
        }
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

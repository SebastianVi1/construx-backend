package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.shared.exception.RuleViolationException;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One import of a PU Win+ workbook into the organization's Catalog and its state machine (docs/pricing.md §1.11):
 * PENDING -> VALIDATING -> VALIDATED -> IMPORTING -> DONE, FAILED from VALIDATING or IMPORTING, CANCELLED from
 * PENDING or VALIDATED. {@code activeOrganizationId} is the organization while the job is active and null after,
 * so its unique constraint allows one active job per organization.
 */
@Entity
@Table(name = "catalog_import", uniqueConstraints = @UniqueConstraint(name = "uk_catalog_import_active",
        columnNames = "active_organization_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogImport {
    /** The key of the licence statement the dashboard shows (§4.4). */
    public static final String LICENCE_STATEMENT = "LICENCE_V1";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "active_organization_id")
    private UUID activeOrganizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ImportStatus status;

    @Column(nullable = false, length = 255)
    private String fileKey;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, columnDefinition = "char(64)")
    private String fileSha256;

    @Column(length = 255)
    private String fasarFileKey;

    @Column(length = 255)
    private String fasarFileName;

    private Long fasarFileSize;

    @Column(name = "fasar_file_sha256", columnDefinition = "char(64)")
    private String fasarFileSha256;

    @Column(name = "uploaded_by_membership_id", nullable = false)
    private UUID uploadedByMembershipId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant validatedAt;

    private Instant confirmedAt;

    private Instant finishedAt;

    private Instant licenceAcceptedAt;

    @Column(name = "licence_accepted_by_membership_id")
    private UUID licenceAcceptedByMembershipId;

    @Column(length = 20)
    private String licenceStatement;

    @JdbcTypeCode(SqlTypes.JSON)
    private String summary;

    @Column(length = 40)
    private String failureCode;

    @Column(length = 500)
    private String failureMessage;

    @Column(length = 40)
    private String failureSheet;

    private Integer failureRow;

    @Column(length = 40)
    private String failureColumn;

    /** A job for an uploaded Presupuesto workbook whose bytes hash to {@code sha256}; validation comes next. */
    public static CatalogImport start(CatalogImportUpload presupuesto, String sha256, Instant at) {
        CatalogImport job = new CatalogImport();
        job.id = UUID.randomUUID();
        job.organizationId = presupuesto.getOrganizationId();
        job.fileKey = presupuesto.getFileKey();
        job.fileName = presupuesto.getFileName();
        job.fileSize = presupuesto.getSizeBytes();
        job.fileSha256 = sha256;
        job.uploadedByMembershipId = presupuesto.getCreatedByMembershipId();
        job.createdAt = at;
        job.moveTo(ImportStatus.PENDING);
        return job;
    }

    /** As {@link #start(CatalogImportUpload, String, Instant)}, with the optional FASAR workbook beside it. */
    public static CatalogImport start(CatalogImportUpload presupuesto, String sha256, CatalogImportUpload fasar,
                                      String fasarSha256, Instant at) {
        CatalogImport job = start(presupuesto, sha256, at);
        if (fasar != null) {
            job.fasarFileKey = fasar.getFileKey();
            job.fasarFileName = fasar.getFileName();
            job.fasarFileSize = fasar.getSizeBytes();
            job.fasarFileSha256 = fasarSha256;
        }
        return job;
    }

    public void startValidating() {
        require(ImportStatus.PENDING);
        moveTo(ImportStatus.VALIDATING);
    }

    /** The dry run succeeded; {@code summary} is the JSON of §4.5. */
    public void validated(String summary, Instant at) {
        require(ImportStatus.VALIDATING);
        this.summary = summary;
        this.validatedAt = at;
        moveTo(ImportStatus.VALIDATED);
    }

    /** Licence must be accepted with the current statement key ({@code LICENCE_NOT_ACCEPTED}). */
    public void confirm(boolean licenceAccepted, String statement, UUID membershipId, Instant at) {
        if (!licenceAccepted || !LICENCE_STATEMENT.equals(statement)) {
            throw new RuleViolationException("LICENCE_NOT_ACCEPTED",
                    "The licence statement must be accepted to import the catalog");
        }
        require(ImportStatus.VALIDATED);
        licenceAcceptedAt = at;
        licenceAcceptedByMembershipId = membershipId;
        licenceStatement = statement;
        confirmedAt = at;
        moveTo(ImportStatus.IMPORTING);
    }

    /** The write re-ran the dry run; its refreshed summary replaces the stored one. */
    public void refreshSummary(String summary) {
        require(ImportStatus.IMPORTING);
        this.summary = summary;
    }

    public void done(Instant at) {
        require(ImportStatus.IMPORTING);
        finishedAt = at;
        moveTo(ImportStatus.DONE);
    }

    public void cancel(Instant at) {
        if (status != ImportStatus.PENDING && status != ImportStatus.VALIDATED) {
            throw new IllegalStateException("Only a pending or validated import can be cancelled");
        }
        finishedAt = at;
        moveTo(ImportStatus.CANCELLED);
    }

    /** Validation or the write failed; nothing was written to the Catalog. */
    public void fail(String code, String message, String sheet, Integer row, String column, Instant at) {
        if (status != ImportStatus.VALIDATING && status != ImportStatus.IMPORTING
                && status != ImportStatus.PENDING) {
            throw new IllegalStateException("Only a running import can fail");
        }
        failureCode = code;
        failureMessage = message == null || message.length() <= 500 ? message : message.substring(0, 500);
        failureSheet = sheet == null || sheet.length() <= 40 ? sheet : sheet.substring(0, 40);
        failureRow = row;
        failureColumn = column == null || column.length() <= 40 ? column : column.substring(0, 40);
        finishedAt = at;
        moveTo(ImportStatus.FAILED);
    }

    private void require(ImportStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("The import is " + status + ", not " + expected);
        }
    }

    private void moveTo(ImportStatus next) {
        status = next;
        activeOrganizationId = next.isActive() ? organizationId : null;
    }
}

package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.shared.exception.RuleViolationException;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A presigned upload slot for an import workbook (docs/pricing.md §4.1). The bytes go straight to storage under
 * {@code fileKey}; a job consumes the slot once.
 */
@Entity
@Table(name = "catalog_import_upload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogImportUpload {
    public static final long MAX_BYTES = 52_428_800L;
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String XLS = "application/vnd.ms-excel";
    public static final String PRESUPUESTO = "PRESUPUESTO";
    public static final String FASAR = "FASAR";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 12)
    private String kind;

    @Column(nullable = false, length = 255)
    private String fileKey;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 120)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant consumedAt;

    /**
     * A Presupuesto must be an {@code .xlsx}; a FASAR workbook may also be an {@code .xls}
     * ({@code IMPORT_FILE_TYPE}); 1 byte to 50 MB ({@code IMPORT_FILE_TOO_LARGE}).
     */
    public static CatalogImportUpload open(UUID organizationId, String kind, String fileName, String contentType,
                                           long sizeBytes, UUID membershipId, Instant at) {
        String name = fileName == null ? "" : fileName.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean xlsx = XLSX.equals(contentType) && lower.endsWith(".xlsx");
        boolean xls = XLS.equals(contentType) && lower.endsWith(".xls");
        boolean typeOk = PRESUPUESTO.equals(kind) ? xlsx : FASAR.equals(kind) && (xlsx || xls);
        if (!typeOk || name.length() > 255) {
            throw new RuleViolationException("IMPORT_FILE_TYPE",
                    "The workbook must be an Excel file of the expected kind");
        }
        if (sizeBytes < 1 || sizeBytes > MAX_BYTES) {
            throw new RuleViolationException("IMPORT_FILE_TOO_LARGE", "The workbook must be at most 50 MB");
        }
        CatalogImportUpload upload = new CatalogImportUpload();
        upload.id = UUID.randomUUID();
        upload.organizationId = organizationId;
        upload.kind = kind;
        upload.fileName = name;
        upload.contentType = contentType;
        upload.sizeBytes = sizeBytes;
        upload.createdByMembershipId = membershipId;
        upload.createdAt = at;
        upload.fileKey = "orgs/" + organizationId + "/catalog-imports/" + upload.id + "/" + UUID.randomUUID()
                + (xlsx ? ".xlsx" : ".xls");
        return upload;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void consume(Instant at) {
        if (consumedAt != null) {
            throw new IllegalStateException("This upload was already used");
        }
        consumedAt = at;
    }
}

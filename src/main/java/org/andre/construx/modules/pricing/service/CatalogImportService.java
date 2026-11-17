package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.pricing.model.CatalogImport;
import org.andre.construx.modules.pricing.model.CatalogImportUpload;
import org.andre.construx.modules.pricing.model.ImportStatus;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.repository.CatalogImportRepository;
import org.andre.construx.modules.pricing.repository.CatalogImportUploadRepository;
import org.andre.construx.modules.pricing.service.ResourceService.PersonRef;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Catalog imports from the PU Win+ Presupuesto workbook (docs/pricing.md §4): presigned upload slots, the job,
 * its state, confirm and cancel. The work itself runs in {@link CatalogImportWorker}. Every endpoint needs
 * MANAGE_PRICES, and uploads and jobs of another organization are not found.
 */
@Service
public class CatalogImportService {
    private static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

    private final CatalogImportUploadRepository uploads;
    private final CatalogImportRepository jobs;
    private final ObjectStorage storage;
    private final CatalogImportWorker worker;
    private final MembershipDirectory memberships;
    private final PersonDirectory people;
    private final ObjectMapper json;

    public CatalogImportService(CatalogImportUploadRepository uploads, CatalogImportRepository jobs,
                                ObjectStorage storage, CatalogImportWorker worker, MembershipDirectory memberships,
                                PersonDirectory people, ObjectMapper json) {
        this.uploads = uploads;
        this.jobs = jobs;
        this.storage = storage;
        this.worker = worker;
        this.memberships = memberships;
        this.people = people;
        this.json = json;
    }

    public record UploadData(UUID uploadId, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    public record FileData(String name, long sizeBytes, String sha256) {}

    public record LicenceData(String statement, Instant acceptedAt, PersonRef acceptedBy) {}

    public record FailureData(String code, String message, String sheet, Integer row, String column) {}

    public record ImportData(UUID id, ImportStatus status, FileData file, FileData fasarFile, PersonRef uploadedBy,
                             Instant createdAt, Instant validatedAt, Instant confirmedAt, Instant finishedAt,
                             LicenceData licence, JsonNode summary, FailureData failure) {}

    @Transactional
    public UploadData requestUpload(OrgScope org, String kind, String fileName, String contentType, long sizeBytes) {
        org.require(PermissionAction.MANAGE_PRICES);
        CatalogImportUpload upload = uploads.save(CatalogImportUpload.open(org.organizationId(), kind, fileName,
                contentType, sizeBytes, org.membershipId(), now()));
        ObjectStorage.PresignedUpload presigned = storage.presignUpload(upload.getFileKey(), contentType, sizeBytes,
                UPLOAD_TTL);
        return new UploadData(upload.getId(), presigned.url(), presigned.headers(), presigned.expiresAt());
    }

    /**
     * Checks the upload (§4.2), hashes the stored workbook and starts a PENDING job; validation runs after commit.
     * The FASAR workbook belongs to a later slice and is refused for now.
     */
    @Transactional
    public ImportData create(OrgScope org, UUID presupuestoUploadId, UUID fasarUploadId) {
        CatalogImportUpload upload = uploads.findByIdAndOrganizationId(presupuestoUploadId, org.organizationId())
                .filter(u -> !u.isConsumed() && CatalogImportUpload.PRESUPUESTO.equals(u.getKind()))
                .orElseThrow(() -> new ResourceNotFoundException("Upload not found"));
        org.require(PermissionAction.MANAGE_PRICES);
        if (fasarUploadId != null) {
            throw new RuleViolationException("VALIDATION_FAILED",
                    "Importing a FASAR workbook is not available yet");
        }
        ObjectStorage.StoredObject stored = storage.head(upload.getFileKey())
                .orElseThrow(() -> new RuleViolationException("IMPORT_UPLOAD_MISSING",
                        "The workbook was not uploaded"));
        if (stored.sizeBytes() != upload.getSizeBytes()) {
            throw new RuleViolationException("IMPORT_UPLOAD_MISMATCH",
                    "The uploaded workbook does not match the announced size");
        }
        requireNoActiveImport(org.organizationId());
        CatalogImport job = CatalogImport.start(upload, worker.sha256(upload.getFileKey()), now());
        upload.consume(now());
        try {
            jobs.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            requireNoActiveImport(org.organizationId());
            throw ex;
        }
        worker.scheduleValidation(job.getId());
        return data(job);
    }

    @Transactional(readOnly = true)
    public ImportData get(OrgScope org, UUID importId) {
        CatalogImport job = load(org, importId);
        org.require(PermissionAction.MANAGE_PRICES);
        return data(job);
    }

    @Transactional
    public ImportData confirm(OrgScope org, UUID importId, boolean licenceAccepted, String statement) {
        load(org, importId);
        org.require(PermissionAction.MANAGE_PRICES);
        CatalogImport job = jobs.lock(importId).orElseThrow();
        job.confirm(licenceAccepted, statement, org.membershipId(), now());
        worker.scheduleWrite(job.getId());
        return data(job);
    }

    @Transactional
    public ImportData cancel(OrgScope org, UUID importId) {
        load(org, importId);
        org.require(PermissionAction.MANAGE_PRICES);
        CatalogImport job = jobs.lock(importId).orElseThrow();
        job.cancel(now());
        return data(job);
    }

    private void requireNoActiveImport(UUID organizationId) {
        jobs.findByActiveOrganizationId(organizationId).ifPresent(active -> {
            throw new PricingConflictException("IMPORT_IN_PROGRESS", "Another catalog import is in progress",
                    Map.of("activeImportId", active.getId()));
        });
    }

    private CatalogImport load(OrgScope org, UUID importId) {
        return jobs.findByIdAndOrganizationId(importId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Import not found"));
    }

    private ImportData data(CatalogImport job) {
        List<UUID> ids = Stream.of(job.getUploadedByMembershipId(), job.getLicenceAcceptedByMembershipId())
                .filter(Objects::nonNull).distinct().toList();
        List<OrganizationMembership> found = memberships.byIds(job.getOrganizationId(), ids);
        Map<UUID, PersonDirectory.Person> persons = people.byUserId(found.stream()
                .map(m -> m.getUserAccount().getId()).toList());
        FileData fasar = job.getFasarFileKey() == null ? null
                : new FileData(job.getFasarFileName(), job.getFasarFileSize(), job.getFasarFileSha256());
        LicenceData licence = job.getLicenceAcceptedAt() == null ? null
                : new LicenceData(job.getLicenceStatement(), job.getLicenceAcceptedAt(),
                person(found, persons, job.getLicenceAcceptedByMembershipId()));
        FailureData failure = job.getFailureCode() == null ? null
                : new FailureData(job.getFailureCode(), job.getFailureMessage(), job.getFailureSheet(),
                job.getFailureRow(), job.getFailureColumn());
        return new ImportData(job.getId(), job.getStatus(),
                new FileData(job.getFileName(), job.getFileSize(), job.getFileSha256()), fasar,
                person(found, persons, job.getUploadedByMembershipId()), job.getCreatedAt(), job.getValidatedAt(),
                job.getConfirmedAt(), job.getFinishedAt(), licence,
                job.getSummary() == null ? null : json.readTree(job.getSummary()), failure);
    }

    private static PersonRef person(List<OrganizationMembership> found, Map<UUID, PersonDirectory.Person> persons,
                                    UUID membershipId) {
        return found.stream().filter(m -> m.getId().equals(membershipId)).findFirst().map(m -> {
            String fullName = PersonDirectory.of(persons, m.getUserAccount().getId()).fullName();
            return new PersonRef(m.getId(),
                    fullName == null || fullName.isBlank() ? m.getUserAccount().getEmail() : fullName);
        }).orElse(membershipId == null ? null : new PersonRef(membershipId, null));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

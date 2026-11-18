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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.util.Set;
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

    /** The short form of the last finished import shown on the Catalog summary (§2.1). */
    public record LastImport(UUID id, ImportStatus status, Instant finishedAt, String fileName) {}

    /** {@code importId} as {@link LastImport}, or null; read inside the caller's transaction. */
    LastImport lastImport(UUID organizationId, UUID importId) {
        return importId == null ? null : jobs.findByIdAndOrganizationId(importId, organizationId)
                .map(job -> new LastImport(job.getId(), job.getStatus(), job.getFinishedAt(), job.getFileName()))
                .orElse(null);
    }

    /**
     * The organization's job in PENDING, VALIDATING, VALIDATED or IMPORTING, or null. Jobs carry file names,
     * hashes and the uploader, so a caller without MANAGE_PRICES never gets one (§4.3).
     */
    ImportData activeImport(OrgScope org) {
        if (!org.can(PermissionAction.MANAGE_PRICES, () -> false, null)) {
            return null;
        }
        return jobs.findByActiveOrganizationId(org.organizationId()).map(this::data).orElse(null);
    }

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
     * Checks the uploads (§4.2), hashes the stored workbooks and starts a PENDING job; validation runs after
     * commit. The FASAR workbook is optional.
     */
    @Transactional
    public ImportData create(OrgScope org, UUID presupuestoUploadId, UUID fasarUploadId) {
        CatalogImportUpload upload = uploads.findByIdAndOrganizationId(presupuestoUploadId, org.organizationId())
                .filter(u -> !u.isConsumed() && CatalogImportUpload.PRESUPUESTO.equals(u.getKind()))
                .orElseThrow(() -> new ResourceNotFoundException("Upload not found"));
        CatalogImportUpload fasar = fasarUploadId == null ? null
                : uploads.findByIdAndOrganizationId(fasarUploadId, org.organizationId())
                .filter(u -> !u.isConsumed() && CatalogImportUpload.FASAR.equals(u.getKind()))
                .orElseThrow(() -> new ResourceNotFoundException("Upload not found"));
        org.require(PermissionAction.MANAGE_PRICES);
        requireStored(upload);
        if (fasar != null) {
            requireStored(fasar);
        }
        requireNoActiveImport(org.organizationId());
        CatalogImport job = CatalogImport.start(upload, worker.sha256(upload.getFileKey()), fasar,
                fasar == null ? null : worker.sha256(fasar.getFileKey()), now());
        upload.consume(now());
        if (fasar != null) {
            fasar.consume(now());
        }
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

    /** The organization's jobs newest first (§4.3), paged as §0 with a fixed order. */
    @Transactional(readOnly = true)
    public CatalogPage<ImportData> list(OrgScope org, Integer page, Integer size) {
        org.require(PermissionAction.MANAGE_PRICES);
        Pageable request = CatalogPage.request(page, size, null, Set.of(),
                Sort.by(Sort.Direction.DESC, "createdAt"), "id");
        Page<CatalogImport> found = jobs.findByOrganizationId(org.organizationId(), request);
        People people = people(org.organizationId(), found.getContent());
        return CatalogPage.of(found, job -> data(job, people));
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

    /** The object is in storage with the announced size. */
    private void requireStored(CatalogImportUpload upload) {
        ObjectStorage.StoredObject stored = storage.head(upload.getFileKey())
                .orElseThrow(() -> new RuleViolationException("IMPORT_UPLOAD_MISSING",
                        "The workbook was not uploaded"));
        if (stored.sizeBytes() != upload.getSizeBytes()) {
            throw new RuleViolationException("IMPORT_UPLOAD_MISMATCH",
                    "The uploaded workbook does not match the announced size");
        }
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
        return data(job, people(job.getOrganizationId(), List.of(job)));
    }

    /** The memberships and names behind the jobs' uploader and licence acceptor, loaded once. */
    private record People(List<OrganizationMembership> memberships, Map<UUID, PersonDirectory.Person> persons) {}

    private People people(UUID organizationId, List<CatalogImport> list) {
        List<UUID> ids = list.stream()
                .flatMap(job -> Stream.of(job.getUploadedByMembershipId(), job.getLicenceAcceptedByMembershipId()))
                .filter(Objects::nonNull).distinct().toList();
        List<OrganizationMembership> found = ids.isEmpty() ? List.of() : memberships.byIds(organizationId, ids);
        return new People(found, people.byUserId(found.stream().map(m -> m.getUserAccount().getId()).toList()));
    }

    private ImportData data(CatalogImport job, People people) {
        FileData fasar = job.getFasarFileKey() == null ? null
                : new FileData(job.getFasarFileName(), job.getFasarFileSize(), job.getFasarFileSha256());
        LicenceData licence = job.getLicenceAcceptedAt() == null ? null
                : new LicenceData(job.getLicenceStatement(), job.getLicenceAcceptedAt(),
                person(people, job.getLicenceAcceptedByMembershipId()));
        FailureData failure = job.getFailureCode() == null ? null
                : new FailureData(job.getFailureCode(), job.getFailureMessage(), job.getFailureSheet(),
                job.getFailureRow(), job.getFailureColumn());
        return new ImportData(job.getId(), job.getStatus(),
                new FileData(job.getFileName(), job.getFileSize(), job.getFileSha256()), fasar,
                person(people, job.getUploadedByMembershipId()), job.getCreatedAt(), job.getValidatedAt(),
                job.getConfirmedAt(), job.getFinishedAt(), licence,
                job.getSummary() == null ? null : json.readTree(job.getSummary()), failure);
    }

    private static PersonRef person(People people, UUID membershipId) {
        return people.memberships().stream().filter(m -> m.getId().equals(membershipId)).findFirst().map(m -> {
            String fullName = PersonDirectory.of(people.persons(), m.getUserAccount().getId()).fullName();
            return new PersonRef(m.getId(),
                    fullName == null || fullName.isBlank() ? m.getUserAccount().getEmail() : fullName);
        }).orElse(membershipId == null ? null : new PersonRef(membershipId, null));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

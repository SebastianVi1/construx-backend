package org.andre.construx.modules.documents.service;

import org.andre.construx.modules.documents.model.DocumentEntry;
import org.andre.construx.modules.documents.model.DocumentFolder;
import org.andre.construx.modules.documents.repository.DocumentEntryRepository;
import org.andre.construx.modules.documents.repository.DocumentFolderRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Document folders and entries per site. Financial folders (requires_permission = VIEW_PRICES) are
 * excluded from all responses for callers who lack that permission — they appear absent, not locked.
 */
@Service
public class DocumentService {

    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofMinutes(10);

    /** Standard folders seeded on every new site, in display order. */
    private static final List<FolderDef> STANDARD_FOLDERS = List.of(
            new FolderDef("Logbook",                   "Bitácora",                   null,          0),
            new FolderDef("Photo Evidence",            "Evidencia fotográfica",       null,          1),
            new FolderDef("Reports",                   "Reportes",                   null,          2),
            new FolderDef("Plans & Documents",         "Planos y documentos",         null,          3),
            new FolderDef("Budget & APU",              "Presupuesto y APU",           "VIEW_PRICES", 4),
            new FolderDef("Purchases & Requisitions",  "Compras y requisiciones",     "VIEW_PRICES", 5),
            new FolderDef("Estimates & Billing",       "Estimaciones y cobranza",     "VIEW_PRICES", 6)
    );

    private record FolderDef(String nameEn, String nameEs, String requiresPermission, int position) {}

    // — Response types —

    public record FolderData(UUID id, String nameEn, String nameEs, boolean standard,
                             String requiresPermission, int position, long entryCount) {}

    public record UploadUrlData(UUID entryId, URI uploadUrl, Map<String, String> headers, Instant expiresAt,
                                String s3Key) {}

    public record EntryData(UUID id, UUID folderId, String fileName, Long fileSize, String fileType,
                            String sourceFeature, Instant createdAt, URI downloadUrl, Instant downloadUrlExpiresAt) {}

    public record SearchHit(UUID entryId, String fileName, UUID folderId, String folderNameEn, String folderNameEs) {}

    // — Dependencies —

    private final DocumentFolderRepository folders;
    private final DocumentEntryRepository entries;
    private final ObjectStorage storage;

    public DocumentService(DocumentFolderRepository folders, DocumentEntryRepository entries, ObjectStorage storage) {
        this.folders = folders;
        this.entries = entries;
        this.storage = storage;
    }

    // — Seeding —

    /**
     * Creates the 7 standard folders for a new site. Idempotent: skips any folder whose English name
     * already exists for the project. Called inside the project creation transaction.
     */
    @Transactional
    public void seedStandardFolders(UUID projectId, Project project) {
        for (FolderDef def : STANDARD_FOLDERS) {
            if (!folders.existsByProjectIdAndNameEn(projectId, def.nameEn())) {
                folders.save(new DocumentFolder(project, def.nameEn(), def.nameEs(),
                        true, def.requiresPermission(), def.position()));
            }
        }
    }

    // — Folder operations —

    @Transactional(readOnly = true)
    public List<FolderData> listFolders(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        boolean canViewPrices = site.scopeOf(PermissionAction.VIEW_PRICES).isPresent();
        return folders.findByProjectIdOrderByPositionAscNameEnAsc(site.siteId()).stream()
                .filter(f -> f.getRequiresPermission() == null || canViewPrices)
                .map(f -> toFolderData(f, entries.countByFolder_Id(f.getId())))
                .toList();
    }

    @Transactional
    public FolderData createFolder(SiteScope site,
                                   String nameEn, String nameEs) {
        site.require(PermissionAction.MANAGE_PROJECT);
        if (nameEn == null || nameEn.isBlank() || nameEn.strip().length() > 120)
            throw new RuleViolationException("INVALID_FOLDER_NAME", "Folder name must be 1 to 120 characters");
        if (nameEs == null || nameEs.isBlank() || nameEs.strip().length() > 120)
            throw new RuleViolationException("INVALID_FOLDER_NAME", "Folder name (ES) must be 1 to 120 characters");
        DocumentFolder folder = folders.save(new DocumentFolder(site.site(), nameEn.strip(), nameEs.strip()));
        return toFolderData(folder, 0);
    }

    @Transactional
    public void deleteFolder(SiteScope site, UUID folderId) {
        site.require(PermissionAction.MANAGE_PROJECT);
        DocumentFolder folder = loadFolder(site.siteId(), folderId);
        if (folder.isStandard()) {
            throw new AccessDeniedException("Standard folders cannot be deleted");
        }
        List<DocumentEntry> folderEntries = entries.findByFolder_IdOrderByCreatedAtDesc(folderId);
        entries.deleteAll(folderEntries);
        entries.flush();
        folders.delete(folder);
        folders.flush();
        folderEntries.forEach(e -> storage.delete(e.getS3Key()));
    }

    // — Entry operations —

    @Transactional(readOnly = true)
    public List<EntryData> listEntries(SiteScope site, UUID folderId) {
        site.require(PermissionAction.VIEW_PROJECT);
        DocumentFolder folder = loadFolder(site.siteId(), folderId);
        requireFolderVisible(site, folder);
        return entries.findByFolder_IdOrderByCreatedAtDesc(folderId).stream()
                .map(this::toEntryData)
                .toList();
    }

    /** Returns a presigned upload URL. The entry row is created only after the client calls confirmUpload. */
    @Transactional(readOnly = true)
    public UploadUrlData requestUploadUrl(SiteScope site,
                                          UUID folderId, String fileName, String contentType, long sizeBytes) {
        site.require(PermissionAction.VIEW_PROJECT);
        DocumentFolder folder = loadFolder(site.siteId(), folderId);
        requireFolderVisible(site, folder);
        if (fileName == null || fileName.isBlank() || fileName.strip().length() > 255)
            throw new RuleViolationException("INVALID_FILE_NAME", "File name must be 1 to 255 characters");
        UUID entryId = UUID.randomUUID();
        String s3Key = "orgs/%s/projects/%s/documents/%s".formatted(site.organizationId(), site.siteId(), entryId);
        ObjectStorage.PresignedUpload upload = storage.presignUpload(s3Key, contentType, sizeBytes, UPLOAD_TTL);
        return new UploadUrlData(entryId, upload.url(), upload.headers(), upload.expiresAt(), s3Key);
    }

    /** Creates the DocumentEntry row after the client has PUT the file to S3. */
    @Transactional
    public EntryData confirmUpload(SiteScope site, UUID folderId,
                                   UUID entryId, String s3Key, String fileName, Long fileSize,
                                   String fileType, String sourceFeature) {
        site.require(PermissionAction.VIEW_PROJECT);
        DocumentFolder folder = loadFolder(site.siteId(), folderId);
        requireFolderVisible(site, folder);
        storage.head(s3Key)
                .orElseThrow(() -> new RuleViolationException("UPLOAD_MISSING", "The file was not uploaded to storage"));
        // Use caller-supplied entryId so the client can correlate its pending upload
        DocumentEntry entry = new DocumentEntry(entryId, folder, site.siteId(), site.organizationId(), s3Key,
                fileName == null ? "" : fileName.strip(), fileSize, fileType,
                sourceFeature == null ? "manual" : sourceFeature, site.userId());
        return toEntryData(entries.save(entry));
    }

    @Transactional
    public void deleteEntry(SiteScope site, UUID folderId, UUID entryId) {
        site.require(PermissionAction.MANAGE_PROJECT);
        loadFolder(site.siteId(), folderId);
        DocumentEntry entry = entries.findByIdAndFolder_Id(entryId, folderId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        entries.delete(entry);
        entries.flush();
        storage.delete(entry.getS3Key());
    }

    // — Search —

    @Transactional(readOnly = true)
    public List<SearchHit> search(SiteScope site, String term) {
        site.require(PermissionAction.VIEW_PROJECT);
        boolean canViewPrices = site.scopeOf(PermissionAction.VIEW_PRICES).isPresent();
        return entries.searchByFileName(site.siteId(), term).stream()
                .filter(e -> {
                    String req = e.getFolder().getRequiresPermission();
                    return req == null || canViewPrices;
                })
                .map(e -> new SearchHit(e.getId(), e.getFileName(),
                        e.getFolder().getId(), e.getFolder().getNameEn(), e.getFolder().getNameEs()))
                .toList();
    }

    // — Helpers —

    private DocumentFolder loadFolder(UUID projectId, UUID folderId) {
        return folders.findById(folderId)
                .filter(f -> f.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Folder not found"));
    }

    private static void requireFolderVisible(SiteScope site, DocumentFolder folder) {
        if (folder.getRequiresPermission() == null) return;
        site.scopeOf(PermissionAction.valueOf(folder.getRequiresPermission()))
                .orElseThrow(() -> new ResourceNotFoundException("Folder not found"));
    }

    private FolderData toFolderData(DocumentFolder f, long entryCount) {
        return new FolderData(f.getId(), f.getNameEn(), f.getNameEs(), f.isStandard(),
                f.getRequiresPermission(), f.getPosition(), entryCount);
    }

    private EntryData toEntryData(DocumentEntry e) {
        Instant expiresAt = Instant.now().plus(VIEW_TTL);
        URI url = storage.presignDownload(e.getS3Key(), VIEW_TTL);
        return new EntryData(e.getId(), e.getFolder().getId(), e.getFileName(), e.getFileSize(),
                e.getFileType(), e.getSourceFeature(), e.getCreatedAt(), url, expiresAt);
    }
}

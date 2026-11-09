package org.andre.construx.modules.documents.controller;


import org.andre.construx.modules.project.access.SiteScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.andre.construx.modules.documents.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/document-folders")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    // — Request types —

    public record CreateFolderRequest(@NotBlank @Size(max = 120) String nameEn,
                                      @NotBlank @Size(max = 120) String nameEs) {}

    public record RequestUploadUrlRequest(@NotBlank @Size(max = 255) String fileName,
                                          @NotBlank @Size(max = 100) String contentType,
                                          @NotNull @Positive Long sizeBytes) {}

    public record ConfirmUploadRequest(@NotNull UUID entryId,
                                       @NotBlank String s3Key,
                                       @NotBlank @Size(max = 255) String fileName,
                                       Long fileSize,
                                       @Size(max = 80) String fileType,
                                       @Size(max = 40) String sourceFeature) {}

    // — Endpoints —

    @GetMapping
    public List<DocumentService.FolderData> listFolders(SiteScope site) {
        return documents.listFolders(site);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.FolderData createFolder(SiteScope site,
                                                    @Valid @RequestBody CreateFolderRequest body) {
        return documents.createFolder(site, body.nameEn(), body.nameEs());
    }

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(SiteScope site,
                             @PathVariable UUID folderId) {
        documents.deleteFolder(site, folderId);
    }

    @GetMapping("/{folderId}/entries")
    public List<DocumentService.EntryData> listEntries(SiteScope site,
                                                        @PathVariable UUID folderId) {
        return documents.listEntries(site, folderId);
    }

    @PostMapping("/{folderId}/entries/upload-url")
    public DocumentService.UploadUrlData requestUploadUrl(SiteScope site,
                                                           @PathVariable UUID folderId,
                                                           @Valid @RequestBody RequestUploadUrlRequest body) {
        return documents.requestUploadUrl(site, folderId,
                body.fileName(), body.contentType(), body.sizeBytes());
    }

    @PostMapping("/{folderId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.EntryData confirmUpload(SiteScope site,
                                                    @PathVariable UUID folderId,
                                                    @Valid @RequestBody ConfirmUploadRequest body) {
        return documents.confirmUpload(site, folderId,
                body.entryId(), body.s3Key(), body.fileName(), body.fileSize(), body.fileType(), body.sourceFeature());
    }

    @DeleteMapping("/{folderId}/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(SiteScope site,
                            @PathVariable UUID folderId,
                            @PathVariable UUID entryId) {
        documents.deleteEntry(site, folderId, entryId);
    }

    @GetMapping("/search")
    public List<DocumentService.SearchHit> search(SiteScope site,
                                                   @RequestParam @NotBlank @Size(max = 100) String q) {
        return documents.search(site, q);
    }
}

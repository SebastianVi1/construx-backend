package org.andre.construx.modules.documents.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.andre.construx.modules.documents.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
    public List<DocumentService.FolderData> listFolders(@PathVariable UUID organizationId,
                                                         @PathVariable UUID projectId,
                                                         Authentication auth) {
        return documents.listFolders(userId(auth), organizationId, projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.FolderData createFolder(@PathVariable UUID organizationId,
                                                    @PathVariable UUID projectId,
                                                    @Valid @RequestBody CreateFolderRequest body,
                                                    Authentication auth) {
        return documents.createFolder(userId(auth), organizationId, projectId, body.nameEn(), body.nameEs());
    }

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(@PathVariable UUID organizationId,
                             @PathVariable UUID projectId,
                             @PathVariable UUID folderId,
                             Authentication auth) {
        documents.deleteFolder(userId(auth), organizationId, projectId, folderId);
    }

    @GetMapping("/{folderId}/entries")
    public List<DocumentService.EntryData> listEntries(@PathVariable UUID organizationId,
                                                        @PathVariable UUID projectId,
                                                        @PathVariable UUID folderId,
                                                        Authentication auth) {
        return documents.listEntries(userId(auth), organizationId, projectId, folderId);
    }

    @PostMapping("/{folderId}/entries/upload-url")
    public DocumentService.UploadUrlData requestUploadUrl(@PathVariable UUID organizationId,
                                                           @PathVariable UUID projectId,
                                                           @PathVariable UUID folderId,
                                                           @Valid @RequestBody RequestUploadUrlRequest body,
                                                           Authentication auth) {
        return documents.requestUploadUrl(userId(auth), organizationId, projectId, folderId,
                body.fileName(), body.contentType(), body.sizeBytes());
    }

    @PostMapping("/{folderId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.EntryData confirmUpload(@PathVariable UUID organizationId,
                                                    @PathVariable UUID projectId,
                                                    @PathVariable UUID folderId,
                                                    @Valid @RequestBody ConfirmUploadRequest body,
                                                    Authentication auth) {
        return documents.confirmUpload(userId(auth), organizationId, projectId, folderId,
                body.entryId(), body.s3Key(), body.fileName(), body.fileSize(), body.fileType(), body.sourceFeature());
    }

    @DeleteMapping("/{folderId}/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(@PathVariable UUID organizationId,
                            @PathVariable UUID projectId,
                            @PathVariable UUID folderId,
                            @PathVariable UUID entryId,
                            Authentication auth) {
        documents.deleteEntry(userId(auth), organizationId, projectId, folderId, entryId);
    }

    @GetMapping("/search")
    public List<DocumentService.SearchHit> search(@PathVariable UUID organizationId,
                                                   @PathVariable UUID projectId,
                                                   @RequestParam @NotBlank @Size(max = 100) String q,
                                                   Authentication auth) {
        return documents.search(userId(auth), organizationId, projectId, q);
    }

    private static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}

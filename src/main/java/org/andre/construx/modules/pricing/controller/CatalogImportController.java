package org.andre.construx.modules.pricing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.model.ImportFileKind;
import org.andre.construx.modules.pricing.service.CatalogImportService;
import org.andre.construx.modules.pricing.service.CatalogImportService.ImportData;
import org.andre.construx.modules.pricing.service.CatalogImportService.UploadData;
import org.andre.construx.modules.pricing.service.CatalogPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Catalog import from the PU Win+ workbook (docs/pricing.md §4.1-4.4). */
@RestController
@RequestMapping("/api/organizations/{organizationId}/catalog-imports")
public class CatalogImportController {
    private final CatalogImportService imports;

    public CatalogImportController(CatalogImportService imports) {
        this.imports = imports;
    }

    public record UploadRequest(@NotNull ImportFileKind kind,
                                @NotBlank String fileName, @NotBlank String contentType, @NotNull Long sizeBytes) {}

    public record CreateRequest(@NotNull UUID presupuestoUploadId, UUID fasarUploadId) {}

    public record ConfirmRequest(Boolean licenceAccepted, String statement) {}

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadData upload(OrgScope org, @Valid @RequestBody UploadRequest body) {
        return imports.requestUpload(org, body.kind(), body.fileName(), body.contentType(), body.sizeBytes());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportData create(OrgScope org, @Valid @RequestBody CreateRequest body) {
        return imports.create(org, body.presupuestoUploadId(), body.fasarUploadId());
    }

    @GetMapping
    public CatalogPage<ImportData> list(OrgScope org, @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return imports.list(org, page, size);
    }

    @GetMapping("/{importId}")
    public ImportData get(OrgScope org, @PathVariable UUID importId) {
        return imports.get(org, importId);
    }

    @PostMapping("/{importId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportData confirm(OrgScope org, @PathVariable UUID importId, @RequestBody ConfirmRequest body) {
        return imports.confirm(org, importId, Boolean.TRUE.equals(body.licenceAccepted()), body.statement());
    }

    @PostMapping("/{importId}/cancel")
    public ImportData cancel(OrgScope org, @PathVariable UUID importId) {
        return imports.cancel(org, importId);
    }
}

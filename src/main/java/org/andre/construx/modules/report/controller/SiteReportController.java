package org.andre.construx.modules.report.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.report.dto.SiteReportResponse;
import org.andre.construx.modules.report.service.SiteReportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/reports")
public class SiteReportController {

    private final SiteReportService reports;

    public SiteReportController(SiteReportService reports) {
        this.reports = reports;
    }

    public record GenerateReportRequest(@NotBlank @Size(max = 200) String title) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SiteReportResponse generate(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                       @Valid @RequestBody GenerateReportRequest body,
                                       Authentication authentication) {
        return reports.generate(userId(authentication), organizationId, projectId, body.title());
    }

    @GetMapping
    public List<SiteReportResponse> list(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                         Authentication authentication) {
        return reports.list(userId(authentication), organizationId, projectId);
    }

    @GetMapping("/{reportId}/download")
    public Map<String, String> download(@PathVariable UUID organizationId, @PathVariable UUID projectId,
                                        @PathVariable UUID reportId, Authentication authentication) {
        URI url = reports.getDownloadUrl(userId(authentication), organizationId, projectId, reportId);
        return Map.of("downloadUrl", url.toString());
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

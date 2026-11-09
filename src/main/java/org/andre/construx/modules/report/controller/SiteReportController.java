package org.andre.construx.modules.report.controller;


import org.andre.construx.modules.project.access.SiteScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.report.dto.SiteReportResponse;
import org.andre.construx.modules.report.service.SiteReportService;
import org.springframework.http.HttpStatus;
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
    public SiteReportResponse generate(SiteScope site,
                                       @Valid @RequestBody GenerateReportRequest body) {
        return reports.generate(site, body.title());
    }

    @GetMapping
    public List<SiteReportResponse> list(SiteScope site) {
        return reports.list(site);
    }

    @GetMapping("/{reportId}/download")
    public Map<String, String> download(SiteScope site,
                                        @PathVariable UUID reportId) {
        URI url = reports.getDownloadUrl(site, reportId);
        return Map.of("downloadUrl", url.toString());
    }
}

package org.andre.construx.modules.report.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record SiteReportResponse(
        UUID id,
        String title,
        String status,
        String generatedByName,
        Instant createdAt,
        Instant completedAt,
        Long sizeBytes,
        @JsonInclude(JsonInclude.Include.NON_NULL) String pdfBase64) {}

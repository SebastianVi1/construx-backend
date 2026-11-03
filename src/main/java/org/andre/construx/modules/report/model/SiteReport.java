package org.andre.construx.modules.report.model;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A snapshot of a Site's budget, expenses, phase progress, and team, generated as a PDF and stored in S3.
 * The id is assigned up-front because it is part of the S3 storage key. The record is created as PENDING,
 * then transitions to READY once the PDF is successfully uploaded, or FAILED on error.
 */
@Entity
@Table(name = "site_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SiteReport {
    public static final int MAX_TITLE_LENGTH = 200;

    @Id
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private OrganizationMembership generatedBy;

    @Column(nullable = false, unique = true, length = 300)
    private String storageKey;

    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SiteReportStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    public SiteReport(Project project, OrganizationMembership generatedBy, String title) {
        this.project = DomainValidation.required(project, "Project is required");
        if (title == null || title.isBlank() || title.strip().length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Report title must have 1 to " + MAX_TITLE_LENGTH + " characters");
        }
        this.title = title.strip();
        this.generatedBy = generatedBy;
        this.id = UUID.randomUUID();
        this.storageKey = "orgs/%s/projects/%s/reports/%s/report.pdf"
                .formatted(project.getOrganization().getId(), project.getId(), id);
        this.status = SiteReportStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markReady(long sizeBytes) {
        if (status != SiteReportStatus.PENDING) {
            throw new IllegalStateException("Report " + id + " is not in PENDING state");
        }
        this.sizeBytes = sizeBytes;
        this.status = SiteReportStatus.READY;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = SiteReportStatus.FAILED;
    }

    public boolean isReady() {
        return status == SiteReportStatus.READY;
    }
}

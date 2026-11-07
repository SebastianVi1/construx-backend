package org.andre.construx.modules.floorplan.model;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A floor plan linked to a site. The original file (PDF or image) is uploaded to private storage by the
 * client using a presigned URL. For PDFs the backend renders the requested page asynchronously to a PNG
 * ({@code renderedKey}). For images the original is copied to the rendered key.
 */
@Entity
@Table(name = "floor_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FloorPlan {

    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_LEVEL_LENGTH = 60;

    /** Accepted content types and the file extensions they get in storage. */
    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(length = MAX_LEVEL_LENGTH)
    private String level;

    @Column(nullable = false)
    private int pageNumber;

    @Column(nullable = false, unique = true, length = 300)
    private String originalKey;

    @Column(length = 300)
    private String renderedKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FloorPlanStatus status;

    @Column(length = 500)
    private String errorReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_membership_id", nullable = false)
    private OrganizationMembership uploadedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    public FloorPlan(Project project, OrganizationMembership uploadedBy,
                     String name, String description, String level,
                     int pageNumber, String contentType, long sizeBytes) {
        this.project = DomainValidation.required(project, "Project is required");
        this.uploadedBy = DomainValidation.required(uploadedBy, "Uploader is required");

        String ct = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        String extension = CONTENT_TYPES.get(ct);
        if (extension == null) {
            throw new RuleViolationException("FLOOR_PLAN_FILE_TYPE",
                    "Floor plans must be PDF, JPEG, PNG or WebP files");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw new RuleViolationException("FLOOR_PLAN_FILE_TOO_LARGE",
                    "Floor plan files must be between 1 byte and 50 MB");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be at least 1");
        }

        this.id = UUID.randomUUID();
        this.originalKey = "orgs/%s/projects/%s/floor-plans/%s/original.%s"
                .formatted(project.getOrganization().getId(), project.getId(), id, extension);
        this.pageNumber = pageNumber;
        rename(name);
        describe(description);
        setLevel(level);
        this.status = FloorPlanStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Floor plan name must have 1 to 120 characters");
        }
        this.name = name.strip();
    }

    public void describe(String description) {
        if (description != null && description.strip().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description must be at most 500 characters");
        }
        this.description = description == null || description.isBlank() ? null : description.strip();
    }

    public void setLevel(String level) {
        if (level != null && level.strip().length() > MAX_LEVEL_LENGTH) {
            throw new IllegalArgumentException("Level must be at most 60 characters");
        }
        this.level = level == null || level.isBlank() ? null : level.strip();
    }

    /** Move from PENDING to PROCESSING when the original file is confirmed in storage. */
    public void startProcessing() {
        if (status != FloorPlanStatus.PENDING) {
            throw new IllegalStateException("Floor plan " + id + " is not PENDING");
        }
        this.status = FloorPlanStatus.PROCESSING;
    }

    /** Move from PROCESSING to READY when the rendered PNG is in storage. */
    public void markReady(String renderedKey, Instant completedAt) {
        if (status != FloorPlanStatus.PROCESSING) {
            throw new IllegalStateException("Floor plan " + id + " is not PROCESSING");
        }
        if (renderedKey == null || renderedKey.isBlank()) {
            throw new IllegalArgumentException("Rendered key is required");
        }
        this.renderedKey = renderedKey;
        this.status = FloorPlanStatus.READY;
        this.completedAt = completedAt;
    }

    /** Move from PROCESSING to ERROR when rendering fails. */
    public void markError(String reason) {
        this.status = FloorPlanStatus.ERROR;
        this.errorReason = reason;
        this.completedAt = Instant.now();
    }

    public boolean isReady() {
        return status == FloorPlanStatus.READY;
    }

    /** Whether the original file is a PDF (needs page rendering). */
    public boolean isPdf() {
        return originalKey != null && originalKey.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}

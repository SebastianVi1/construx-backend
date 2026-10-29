package org.andre.construx.modules.plan.model;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.storage.ObjectStorage.StoredObject;
import org.andre.construx.shared.validation.DomainValidation;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A plan of a site: one image file kept in private storage. The id is assigned up front because the storage
 * key contains it; the row exists (PENDING) before the file does, and turns READY once the stored file matches
 * the announced size and type.
 */
@Entity
@Table(name = "project_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectPlan {
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    /** Accepted content types and the extension their files get in storage. */
    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    @Id
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(nullable = false, length = 255)
    private String originalFileName;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, unique = true, length = 300)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_membership_id")
    private OrganizationMembership uploadedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    /** A blank {@code name} falls back to the file name without its extension. */
    public ProjectPlan(Project project, OrganizationMembership uploadedBy, String fileName, String contentType,
                       long sizeBytes, String name, String description) {
        this.project = DomainValidation.required(project, "Project is required");
        this.uploadedBy = uploadedBy;
        if (fileName == null || fileName.isBlank() || fileName.strip().length() > 255) {
            throw new IllegalArgumentException("File name must have 1 to 255 characters");
        }
        this.originalFileName = fileName.strip();
        this.contentType = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        String extension = CONTENT_TYPES.get(this.contentType);
        if (extension == null) {
            throw new RuleViolationException("PLAN_FILE_TYPE", "Plans must be JPEG, PNG or WebP images");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw new RuleViolationException("PLAN_FILE_TOO_LARGE", "Plans must be between 1 byte and 20 MB");
        }
        this.sizeBytes = sizeBytes;
        rename(name == null || name.isBlank() ? defaultName(originalFileName) : name);
        describe(description);
        this.id = UUID.randomUUID();
        this.storageKey = "orgs/%s/projects/%s/plans/%s/%s.%s".formatted(project.getOrganization().getId(),
                project.getId(), id, UUID.randomUUID(), extension);
        this.status = PlanStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Plan name must have 1 to 120 characters");
        }
        this.name = name.strip();
    }

    public void describe(String description) {
        if (description != null && description.strip().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Plan description must have at most 500 characters");
        }
        this.description = description == null || description.isBlank() ? null : description.strip();
    }

    /** The file in storage must be exactly what was announced; anything else stays PENDING. */
    public void markReady(StoredObject stored) {
        if (status != PlanStatus.PENDING) {
            throw new IllegalStateException("Plan " + id + " is already uploaded");
        }
        String storedType = stored.contentType() == null ? "" : stored.contentType().toLowerCase(Locale.ROOT);
        if (stored.sizeBytes() != sizeBytes || !storedType.equals(contentType)) {
            throw new RuleViolationException("PLAN_UPLOAD_MISMATCH", "The uploaded file does not match the plan");
        }
        this.status = PlanStatus.READY;
        this.completedAt = Instant.now();
    }

    public boolean isReady() {
        return status == PlanStatus.READY;
    }

    private static String defaultName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0 ? fileName.substring(0, dot) : fileName).strip();
        return base.length() > MAX_NAME_LENGTH ? base.substring(0, MAX_NAME_LENGTH).strip() : base;
    }
}

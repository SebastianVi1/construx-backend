package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.floorplan.model.FloorPlan;
import org.andre.construx.modules.floorplan.model.FloorPlanStatus;
import org.andre.construx.modules.floorplan.repository.FloorPlanRepository;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.andre.construx.shared.storage.ObjectStorage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Floor plans for a site. Original file (PDF or image) is uploaded via presigned URL by the client.
 * On complete, the original is verified in storage, then processed asynchronously:
 * PDFs are rendered to PNG at 250 DPI; images are stored as-is at the rendered key.
 */
@Service
public class FloorPlanService {

    private static final Logger log = LoggerFactory.getLogger(FloorPlanService.class);

    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofHours(1);

    private final FloorPlanRepository floorPlans;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final ObjectStorage storage;

    public FloorPlanService(FloorPlanRepository floorPlans, ProjectService projects, MembershipGuard guard,
                            DomainAuthorization authorization, ObjectStorage storage) {
        this.floorPlans = floorPlans;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
        this.storage = storage;
    }

    public record UploadData(UUID floorPlanId, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    public record FloorPlanData(UUID id, UUID projectId, String name, String description, String level,
                                int pageNumber, FloorPlanStatus status, String errorReason,
                                URI viewUrl, Instant viewUrlExpiresAt, Instant createdAt, Instant completedAt) {}

    @Transactional
    public UploadData requestUpload(UUID userId, UUID organizationId, UUID projectId,
                                    String name, String description, String level,
                                    int pageNumber, String contentType, long sizeBytes) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);

        FloorPlan plan = new FloorPlan(project, member, name, description, level, pageNumber, contentType, sizeBytes);
        floorPlans.save(plan);

        ObjectStorage.PresignedUpload upload = storage.presignUpload(
                plan.getOriginalKey(), contentType.strip().toLowerCase(java.util.Locale.ROOT), sizeBytes, UPLOAD_TTL);
        return new UploadData(plan.getId(), upload.url(), upload.headers(), upload.expiresAt());
    }

    /**
     * Verifies the original file is in storage, then fires async rendering.
     * Returns immediately with status=PROCESSING.
     */
    @Transactional
    public FloorPlanData completeUpload(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);
        FloorPlan plan = load(projectId, floorPlanId);
        storage.head(plan.getOriginalKey())
                .orElseThrow(() -> new RuleViolationException("FLOOR_PLAN_UPLOAD_MISSING",
                        "The floor plan file was not uploaded"));
        plan.startProcessing();
        floorPlans.save(plan);
        processAsync(floorPlanId);
        return toData(plan);
    }

    /**
     * Async PDF rendering or image copy. Loads a fresh entity inside its own transaction.
     * Runs on the floorPlanExecutor thread pool.
     */
    @Async("floorPlanExecutor")
    public void processAsync(UUID floorPlanId) {
        doProcess(floorPlanId);
    }

    @Transactional
    public void doProcess(UUID floorPlanId) {
        FloorPlan plan = floorPlans.findById(floorPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
        if (plan.getStatus() != FloorPlanStatus.PROCESSING) {
            log.warn("Floor plan {} is not PROCESSING, skipping render", floorPlanId);
            return;
        }

        String orgId = plan.getProject().getOrganization().getId().toString();
        String projId = plan.getProject().getId().toString();
        String renderedKey = "orgs/%s/projects/%s/floor-plans/%s/rendered.png"
                .formatted(orgId, projId, floorPlanId);

        try {
            byte[] originalBytes = storage.getBytes(plan.getOriginalKey());
            byte[] pngBytes = renderToPng(originalBytes, plan);
            storage.put(renderedKey, pngBytes, "image/png");
            plan.markReady(renderedKey, Instant.now());
            floorPlans.save(plan);
        } catch (PageOutOfRangeException e) {
            plan.markError(e.getMessage());
            floorPlans.save(plan);
        } catch (Exception e) {
            log.error("Failed to process floor plan {}", floorPlanId, e);
            plan.markError("Rendering failed: " + e.getMessage());
            floorPlans.save(plan);
        }
    }

    @Transactional(readOnly = true)
    public FloorPlanData getFloorPlan(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);
        return toData(load(projectId, floorPlanId));
    }

    @Transactional(readOnly = true)
    public List<FloorPlanData> listFloorPlans(UUID userId, UUID organizationId, UUID projectId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);
        return floorPlans.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toData).toList();
    }

    @Transactional
    public void deleteFloorPlan(UUID userId, UUID organizationId, UUID projectId, UUID floorPlanId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);
        FloorPlan plan = load(projectId, floorPlanId);
        floorPlans.delete(plan);
        floorPlans.flush();
        storage.delete(plan.getOriginalKey());
        if (plan.getRenderedKey() != null) {
            storage.delete(plan.getRenderedKey());
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private FloorPlan load(UUID projectId, UUID floorPlanId) {
        return floorPlans.findByIdAndProjectId(floorPlanId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Floor plan not found"));
    }

    FloorPlanData toData(FloorPlan plan) {
        URI viewUrl = null;
        Instant viewUrlExpiresAt = null;
        if (plan.isReady() && plan.getRenderedKey() != null) {
            viewUrlExpiresAt = Instant.now().plus(VIEW_TTL);
            viewUrl = storage.presignDownload(plan.getRenderedKey(), VIEW_TTL);
        }
        return new FloorPlanData(
                plan.getId(),
                plan.getProject().getId(),
                plan.getName(),
                plan.getDescription(),
                plan.getLevel(),
                plan.getPageNumber(),
                plan.getStatus(),
                plan.getErrorReason(),
                viewUrl,
                viewUrlExpiresAt,
                plan.getCreatedAt(),
                plan.getCompletedAt()
        );
    }

    private byte[] renderToPng(byte[] originalBytes, FloorPlan plan) throws Exception {
        if (plan.isPdf()) {
            return renderPdfPage(originalBytes, plan.getPageNumber());
        }
        // For images, the original bytes are the rendered version
        return originalBytes;
    }

    private byte[] renderPdfPage(byte[] pdfBytes, int pageNumber) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            if (pageNumber < 1 || pageNumber > totalPages) {
                throw new PageOutOfRangeException(
                        "Page %d is out of range; this PDF has %d page(s)".formatted(pageNumber, totalPages));
            }
            PDFRenderer renderer = new PDFRenderer(document);
            // pageNumber is 1-based; PDFRenderer uses 0-based index
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, 250);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    /** Signals that the requested page number is out of range (sets floor plan to ERROR). */
    static final class PageOutOfRangeException extends RuntimeException {
        PageOutOfRangeException(String message) {
            super(message);
        }
    }
}

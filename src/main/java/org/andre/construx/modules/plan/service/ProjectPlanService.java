package org.andre.construx.modules.plan.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.plan.model.PlanStatus;
import org.andre.construx.modules.plan.model.ProjectPlan;
import org.andre.construx.modules.plan.repository.ProjectPlanRepository;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans of a site. The file never passes through the API: {@link #requestUpload} hands out a presigned URL,
 * the client sends the bytes to storage, and {@link #complete} checks them there. Order of checks: membership
 * (404), the site (404), VIEW_PLAN or MANAGE_PLAN (403), then the plan inside that site (404).
 */
@Service
public class ProjectPlanService {
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration VIEW_TTL = Duration.ofMinutes(10);

    private final ProjectPlanRepository plans;
    private final ProjectService projects;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final ObjectStorage storage;

    public ProjectPlanService(ProjectPlanRepository plans, ProjectService projects, MembershipGuard guard,
                              DomainAuthorization authorization, ObjectStorage storage) {
        this.plans = plans;
        this.projects = projects;
        this.guard = guard;
        this.authorization = authorization;
        this.storage = storage;
    }

    /** {@code uploadUrl} takes one PUT with exactly {@code headers}; it expires at {@code expiresAt}. */
    public record UploadData(UUID planId, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {}

    /** {@code viewUrl} is a short-lived download link, valid until {@code viewUrlExpiresAt}. */
    public record PlanData(UUID id, UUID projectId, String name, String description, String fileName,
                           String contentType, long sizeBytes, Instant createdAt, Instant completedAt, URI viewUrl,
                           Instant viewUrlExpiresAt) {}

    @Transactional
    public UploadData requestUpload(UUID userId, UUID organizationId, UUID projectId, String fileName,
                                    String contentType, long sizeBytes, String name, String description) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);
        ProjectPlan plan = plans.save(new ProjectPlan(project, member, fileName, contentType, sizeBytes, name,
                description));
        ObjectStorage.PresignedUpload upload = storage.presignUpload(plan.getStorageKey(), plan.getContentType(),
                plan.getSizeBytes(), UPLOAD_TTL);
        return new UploadData(plan.getId(), upload.url(), upload.headers(), upload.expiresAt());
    }

    /** Turns the plan READY once storage holds a file of the announced size and type. */
    @Transactional
    public PlanData complete(UUID userId, UUID organizationId, UUID projectId, UUID planId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);
        ProjectPlan plan = load(projectId, planId);
        ObjectStorage.StoredObject stored = storage.head(plan.getStorageKey())
                .orElseThrow(() -> new RuleViolationException("PLAN_UPLOAD_MISSING", "The plan file was not uploaded"));
        plan.markReady(stored);
        return toData(plan);
    }

    /** Only READY plans, newest first. */
    @Transactional(readOnly = true)
    public List<PlanData> list(UUID userId, UUID organizationId, UUID projectId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);
        return plans.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, PlanStatus.READY).stream()
                .map(this::toData).toList();
    }

    @Transactional(readOnly = true)
    public PlanData get(UUID userId, UUID organizationId, UUID projectId, UUID planId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PLAN);
        ProjectPlan plan = load(projectId, planId);
        if (!plan.isReady()) {
            throw new ResourceNotFoundException("Plan not found");
        }
        return toData(plan);
    }

    /** Removes the row, then the file; if storage fails the row is kept, so nothing is left unreachable. */
    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID projectId, UUID planId) {
        guard.requireActive(userId, organizationId);
        projects.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PLAN);
        ProjectPlan plan = load(projectId, planId);
        plans.delete(plan);
        plans.flush();
        storage.delete(plan.getStorageKey());
    }

    private ProjectPlan load(UUID projectId, UUID planId) {
        return plans.findByIdAndProjectId(planId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
    }

    private PlanData toData(ProjectPlan plan) {
        Instant viewExpiresAt = Instant.now().plus(VIEW_TTL);
        URI viewUrl = plan.isReady() ? storage.presignDownload(plan.getStorageKey(), VIEW_TTL) : null;
        return new PlanData(plan.getId(), plan.getProject().getId(), plan.getName(), plan.getDescription(),
                plan.getOriginalFileName(), plan.getContentType(), plan.getSizeBytes(), plan.getCreatedAt(),
                plan.getCompletedAt(), viewUrl, viewUrl == null ? null : viewExpiresAt);
    }
}

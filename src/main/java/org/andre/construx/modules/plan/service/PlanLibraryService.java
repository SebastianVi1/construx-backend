package org.andre.construx.modules.plan.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.model.*;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.plan.model.*;
import org.andre.construx.modules.plan.repository.*;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PlanLibraryService {
    private final PlanFolderRepository folders;
    private final ProjectPlanRepository plans;
    private final ProjectPlanService planService;
    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final EntityManager entityManager;

    public PlanLibraryService(PlanFolderRepository folders, ProjectPlanRepository plans, ProjectPlanService planService,
            ProjectRepository projects, ProjectAccessRepository accesses, MembershipGuard guard,
            DomainAuthorization authorization, EntityManager entityManager) {
        this.folders = folders;
        this.plans = plans;
        this.planService = planService;
        this.projects = projects;
        this.accesses = accesses;
        this.guard = guard;
        this.authorization = authorization;
        this.entityManager = entityManager;
    }

    public record FolderData(UUID id, UUID parentId, String name) {}
    public record SiteData(UUID id, String name, boolean canManage) {}
    public record LibraryData(List<FolderData> folders, List<SiteData> sites,
                              List<ProjectPlanService.PlanData> plans, boolean canManageFolders) {}

    @Transactional(readOnly = true)
    public LibraryData library(UUID userId, UUID orgId) {
        OrganizationMembership member = guard.requireActive(userId, orgId);
        PermissionScope view = authorization.scopeOf(userId, orgId, PermissionAction.VIEW_PLAN)
                .orElseThrow(() -> new AccessDeniedException("Plan permission required"));
        Set<UUID> assigned = accesses.findProjectIdsByMembership(member.getId());
        PermissionScope manage = authorization.scopeOf(userId, orgId, PermissionAction.MANAGE_PLAN).orElse(null);
        List<SiteData> sites = projects.findInOrganization(orgId, List.of(ProjectStatus.values())).stream()
                .filter(site -> view == PermissionScope.ORGANIZATION
                        || view == PermissionScope.PROJECT && assigned.contains(site.getId()))
                .map(site -> new SiteData(site.getId(), site.getName(), site.getStatus() != ProjectStatus.ARCHIVED
                        && (manage == PermissionScope.ORGANIZATION
                        || manage == PermissionScope.PROJECT && assigned.contains(site.getId()))))
                .toList();
        // Only sign URLs for visible sites. Shared folder labels have no hidden-site counts or metadata.
        List<UUID> siteIds = sites.stream().map(SiteData::id).toList();
        List<ProjectPlanService.PlanData> visible = siteIds.isEmpty() ? List.of()
                : plans.findByProjectIdInAndStatusOrderByCreatedAtDesc(siteIds, PlanStatus.READY).stream()
                        .map(planService::toData).toList();
        return new LibraryData(folders.findByOrganizationIdOrderByName(orgId).stream().map(this::data).toList(),
                sites, visible, managesFolders(member));
    }

    @Transactional
    public FolderData create(UUID userId, UUID orgId, String name, UUID parentId) {
        OrganizationMembership member = requireFolderManager(userId, orgId);
        lock(orgId);
        return data(folders.save(new PlanFolder(member.getOrganization(), folder(orgId, parentId), name)));
    }

    @Transactional
    public FolderData update(UUID userId, UUID orgId, UUID id, String name, UUID parentId) {
        requireFolderManager(userId, orgId);
        lock(orgId);
        PlanFolder target = folder(orgId, id);
        PlanFolder parent = folder(orgId, parentId);
        for (PlanFolder cursor = parent; cursor != null; cursor = cursor.getParent()) {
            if (cursor.getId().equals(id)) throw new RuleViolationException("PLAN_FOLDER_CYCLE", "A folder cannot contain itself");
        }
        target.update(parent, name);
        return data(target);
    }

    /** Promote children and plans to the parent. Never delete a plan or its storage object. */
    @Transactional
    public void delete(UUID userId, UUID orgId, UUID id) {
        requireFolderManager(userId, orgId);
        lock(orgId);
        PlanFolder target = folder(orgId, id);
        folders.findByParentId(id).forEach(child -> child.update(target.getParent(), child.getName()));
        plans.findByFolderId(id).forEach(plan -> plan.moveTo(target.getParent()));
        entityManager.flush();
        folders.delete(target);
    }

    @Transactional
    public ProjectPlanService.PlanData movePlan(UUID userId, UUID orgId, UUID siteId, UUID id, UUID folderId) {
        guard.requireActive(userId, orgId);
        projects.findByIdAndOrganizationId(siteId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Site not found"));
        authorization.require(userId, orgId, siteId, null, PermissionAction.MANAGE_PLAN);
        lock(orgId);
        ProjectPlan plan = plans.findByIdAndProjectId(id, siteId).filter(ProjectPlan::isReady)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
        plan.moveTo(folder(orgId, folderId));
        return planService.toData(plan);
    }

    private OrganizationMembership requireFolderManager(UUID userId, UUID orgId) {
        OrganizationMembership member = guard.requireActive(userId, orgId);
        if (!managesFolders(member)) throw new AccessDeniedException("Only owners and administrators manage folders");
        authorization.require(userId, orgId, null, null, PermissionAction.MANAGE_PLAN);
        return member;
    }

    private boolean managesFolders(OrganizationMembership member) {
        return Set.of("OWNER", "ADMINISTRATOR").contains(member.getRole().getCode());
    }

    /** Serialize structural edits, including plan moves, to prevent cycles and dangling references. */
    private void lock(UUID orgId) {
        entityManager.find(Organization.class, orgId, LockModeType.PESSIMISTIC_WRITE);
    }

    private PlanFolder folder(UUID orgId, UUID id) {
        return id == null ? null : folders.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder not found"));
    }

    private FolderData data(PlanFolder folder) {
        return new FolderData(folder.getId(), folder.getParent() == null ? null : folder.getParent().getId(), folder.getName());
    }
}

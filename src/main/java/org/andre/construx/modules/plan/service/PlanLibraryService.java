package org.andre.construx.modules.plan.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.andre.construx.modules.organization.model.*;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.plan.model.*;
import org.andre.construx.modules.plan.repository.*;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.exception.RuleViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PlanLibraryService {
    private final PlanFolderRepository folders;
    private final ProjectPlanRepository plans;
    private final ProjectPlanService planService;
    private final ProjectService projects;
    private final ProjectAccessService accesses;
    private final EntityManager entityManager;

    public PlanLibraryService(PlanFolderRepository folders, ProjectPlanRepository plans, ProjectPlanService planService,
            ProjectService projects, ProjectAccessService accesses, EntityManager entityManager) {
        this.folders = folders;
        this.plans = plans;
        this.planService = planService;
        this.projects = projects;
        this.accesses = accesses;
        this.entityManager = entityManager;
    }

    public record FolderData(UUID id, UUID parentId, String name) {}
    public record SiteData(UUID id, String name, boolean canManage) {}
    public record LibraryData(List<FolderData> folders, List<SiteData> sites,
                              List<ProjectPlanService.PlanData> plans, boolean canManageFolders) {}

    @Transactional(readOnly = true)
    public LibraryData library(OrgScope org) {
        UUID orgId = org.organizationId();
        PermissionScope view = org.scopeOf(PermissionAction.VIEW_PLAN)
                .orElseThrow(() -> new AccessDeniedException("Plan permission required"));
        Set<UUID> assigned = accesses.siteIdsOf(org.membershipId());
        PermissionScope manage = org.scopeOf(PermissionAction.MANAGE_PLAN).orElse(null);
        List<SiteData> sites = projects.inOrganization(orgId, EnumSet.allOf(ProjectStatus.class)).stream()
                .filter(site -> view == PermissionScope.ORGANIZATION
                        || view == PermissionScope.PROJECT && assigned.contains(site.id()))
                .map(site -> new SiteData(site.id(), site.name(), site.status() != ProjectStatus.ARCHIVED
                        && (manage == PermissionScope.ORGANIZATION
                        || manage == PermissionScope.PROJECT && assigned.contains(site.id()))))
                .toList();
        // Only sign URLs for visible sites. Shared folder labels have no hidden-site counts or metadata.
        List<UUID> siteIds = sites.stream().map(SiteData::id).toList();
        List<ProjectPlanService.PlanData> visible = siteIds.isEmpty() ? List.of()
                : plans.findByProjectIdInAndStatusOrderByCreatedAtDesc(siteIds, PlanStatus.READY).stream()
                        .map(planService::toData).toList();
        return new LibraryData(folders.findByOrganizationIdOrderByName(orgId).stream().map(this::data).toList(),
                sites, visible, managesFolders(org.membership()));
    }

    @Transactional
    public FolderData create(OrgScope org, String name, UUID parentId) {
        requireFolderManager(org);
        UUID orgId = org.organizationId();
        lock(orgId);
        return data(folders.save(new PlanFolder(org.membership().getOrganization(), folder(orgId, parentId), name)));
    }

    @Transactional
    public FolderData update(OrgScope org, UUID id, String name, UUID parentId) {
        requireFolderManager(org);
        UUID orgId = org.organizationId();
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
    public void delete(OrgScope org, UUID id) {
        requireFolderManager(org);
        UUID orgId = org.organizationId();
        lock(orgId);
        PlanFolder target = folder(orgId, id);
        folders.findByParentId(id).forEach(child -> child.update(target.getParent(), child.getName()));
        plans.findByFolderId(id).forEach(plan -> plan.moveTo(target.getParent()));
        entityManager.flush();
        folders.delete(target);
    }

    @Transactional
    public ProjectPlanService.PlanData movePlan(SiteScope site, UUID id, UUID folderId) {
        site.require(PermissionAction.MANAGE_PLAN);
        UUID orgId = site.organizationId();
        lock(orgId);
        ProjectPlan plan = plans.findByIdAndProjectId(id, site.siteId()).filter(ProjectPlan::isReady)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
        plan.moveTo(folder(orgId, folderId));
        return planService.toData(plan);
    }

    private static void requireFolderManager(OrgScope org) {
        if (!managesFolders(org.membership())) throw new AccessDeniedException("Only owners and administrators manage folders");
        org.require(PermissionAction.MANAGE_PLAN);
    }

    private static boolean managesFolders(OrganizationMembership member) {
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

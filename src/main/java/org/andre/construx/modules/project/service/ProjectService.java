package org.andre.construx.modules.project.service;

import org.andre.construx.modules.documents.service.DocumentService;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sites. Callers arrive with an {@link OrgScope} or {@link SiteScope} (membership and tenant-filtered Site already
 * 404-checked); each method adds the permission check (403). Other modules get {@link SiteSummary} records, never the
 * Project entity.
 */
@Service
public class ProjectService {
    private static final Set<ProjectStatus> NOT_ARCHIVED = EnumSet.complementOf(EnumSet.of(ProjectStatus.ARCHIVED));

    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;
    private final ActivityRepository activities;
    private final GoalRepository goals;
    private final EntityManager entityManager;
    private final DocumentService documentService;

    public ProjectService(ProjectRepository projects, ProjectAccessRepository accesses, ActivityRepository activities,
                          GoalRepository goals, EntityManager entityManager, DocumentService documentService) {
        this.projects = projects;
        this.accesses = accesses;
        this.activities = activities;
        this.goals = goals;
        this.entityManager = entityManager;
        this.documentService = documentService;
    }

    /** Optional dates a PATCH may remove; JSON cannot tell "not sent" from null. */
    private static final Set<String> CLEARABLE = Set.of("startDate", "expectedEndDate");

    /**
     * Null fields mean "not sent": on update they keep the stored value. {@code clear} names dates to remove.
     */
    public record ProjectInput(String name, String address, LocalDate startDate, LocalDate expectedEndDate,
                               ProjectStatus status, Set<String> clear) {
        public ProjectInput {
            clear = clear == null ? Set.of() : Set.copyOf(clear);
        }
    }

    /** Counts are null when the caller cannot view this site's tasks; the budget, without VIEW_BUDGET. */
    public record ProjectData(UUID id, String name, String address, LocalDate startDate, LocalDate expectedEndDate,
                              ProjectStatus status, Long activityCount, Long doneCount, BigDecimal allocatedBudget) {}

    /** A Site as other modules see it. */
    public record SiteSummary(UUID id, String name, ProjectStatus status, BigDecimal allocatedBudget) {}

    @Transactional
    public ProjectData create(OrgScope org, ProjectInput input) {
        org.require(PermissionAction.MANAGE_PROJECT);
        Project project = new Project(org.membership().getOrganization(), input.name());
        project.describe(input.address(), input.startDate(), input.expectedEndDate());
        if (input.status() != null) {
            project.changeStatus(input.status());
        }
        Project saved = projects.save(project);
        documentService.seedStandardFolders(saved.getId(), saved);
        return withCounts(org, List.of(saved)).get(0);
    }

    @Transactional(readOnly = true)
    public List<ProjectData> list(OrgScope org, ProjectStatus status) {
        PermissionScope scope = org.scopeOf(PermissionAction.VIEW_PROJECT)
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
        Set<ProjectStatus> statuses = status == null ? NOT_ARCHIVED : EnumSet.of(status);
        List<Project> visible = scope == PermissionScope.ORGANIZATION
                ? projects.findInOrganization(org.organizationId(), statuses)
                : projects.findAccessibleInOrganization(org.organizationId(), statuses, org.membershipId());
        return withCounts(org, visible);
    }

    @Transactional(readOnly = true)
    public ProjectData get(SiteScope site) {
        site.require(PermissionAction.VIEW_PROJECT);
        return withCounts(site.org(), List.of(site.site())).get(0);
    }

    @Transactional
    public ProjectData update(SiteScope site, ProjectInput input) {
        site.require(PermissionAction.MANAGE_PROJECT);
        Project project = site.site();
        if (input.name() != null) {
            project.rename(input.name());
        }
        for (String field : input.clear()) {
            if (!CLEARABLE.contains(field)) {
                throw new IllegalArgumentException("Only startDate and expectedEndDate can be cleared");
            }
        }
        boolean clearStart = input.clear().contains("startDate");
        boolean clearEnd = input.clear().contains("expectedEndDate");
        if ((clearStart && input.startDate() != null) || (clearEnd && input.expectedEndDate() != null)) {
            throw new IllegalArgumentException("A date cannot be set and cleared in the same request");
        }
        project.describe(input.address() != null ? input.address() : project.getAddress(),
                clearStart ? null : input.startDate() != null ? input.startDate() : project.getStartDate(),
                clearEnd ? null : input.expectedEndDate() != null ? input.expectedEndDate() : project.getExpectedEndDate());
        if (input.status() != null) {
            project.changeStatus(input.status());
        }
        projects.save(project);
        return withCounts(site.org(), List.of(project)).get(0);
    }

    /**
     * Sets or, with null, clears the site's allocated budget. Requires MANAGE_BUDGET. It cannot drop below what
     * the site's phases already hold.
     */
    @Transactional
    public ProjectData assignBudget(SiteScope site, BigDecimal amount) {
        site.require(PermissionAction.MANAGE_BUDGET);
        Project project = lockForBudget(site);
        project.assignBudget(amount, allocatedToPhases(site.siteId()));
        return withCounts(site.org(), List.of(project)).get(0);
    }

    /**
     * Reloads the site with fresh state under a row lock, so every budget change inside one site (its own, its
     * phases') runs one at a time and the allocation cap holds under concurrent requests.
     */
    Project lockForBudget(SiteScope site) {
        Project project = entityManager.find(Project.class, site.siteId());
        entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        return project;
    }

    /** Sum of the site's phase budgets. */
    public BigDecimal allocatedToPhases(UUID projectId) {
        return goals.sumBudgetByProject(projectId).setScale(2);
    }

    /** All non-archived sites of the organization, ordered by name. Used by org-level aggregation; callers authorize. */
    @Transactional(readOnly = true)
    public List<SiteSummary> inOrganization(UUID organizationId) {
        return inOrganization(organizationId, NOT_ARCHIVED);
    }

    /** Sites of the organization in the given statuses, ordered by name. No permission check: callers authorize. */
    @Transactional(readOnly = true)
    public List<SiteSummary> inOrganization(UUID organizationId, Set<ProjectStatus> statuses) {
        return projects.findInOrganization(organizationId, statuses).stream()
                .map(p -> new SiteSummary(p.getId(), p.getName(), p.getStatus(), p.getAllocatedBudget()))
                .toList();
    }

    /** Budget amounts are visible to any role with VIEW_BUDGET; everyone else gets null. */
    static boolean canViewBudget(OrgScope org) {
        return org.scopeOf(PermissionAction.VIEW_BUDGET).isPresent();
    }

    private List<ProjectData> withCounts(OrgScope org, List<Project> list) {
        PermissionScope activityScope = org.scopeOf(PermissionAction.VIEW_ACTIVITY).orElse(null);
        Set<UUID> covered = new HashSet<>();
        if (activityScope == PermissionScope.ORGANIZATION) {
            list.forEach(p -> covered.add(p.getId()));
        } else if (activityScope == PermissionScope.PROJECT) {
            Set<UUID> assigned = accesses.findProjectIdsByMembership(org.membershipId());
            list.stream().map(Project::getId).filter(assigned::contains).forEach(covered::add);
        }
        Map<UUID, long[]> counts = new HashMap<>();
        if (!covered.isEmpty()) {
            for (Object[] row : activities.countByProject(covered)) {
                counts.put((UUID) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            }
        }
        boolean budgetVisible = canViewBudget(org);
        return list.stream().map(p -> {
            boolean visible = covered.contains(p.getId());
            long[] c = counts.getOrDefault(p.getId(), new long[] {0, 0});
            return new ProjectData(p.getId(), p.getName(), p.getAddress(), p.getStartDate(), p.getExpectedEndDate(),
                    p.getStatus(), visible ? c[0] : null, visible ? c[1] : null,
                    budgetVisible ? p.getAllocatedBudget() : null);
        }).toList();
    }
}

package org.andre.construx.modules.project.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.model.PermissionScope;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sites. Every call: active membership (404), tenant-filtered load (404), permission (403). */
@Service
public class ProjectService {
    private static final Set<ProjectStatus> NOT_ARCHIVED = EnumSet.complementOf(EnumSet.of(ProjectStatus.ARCHIVED));

    private final ProjectRepository projects;
    private final ProjectAccessRepository accesses;
    private final ActivityRepository activities;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public ProjectService(ProjectRepository projects, ProjectAccessRepository accesses, ActivityRepository activities,
                          MembershipGuard guard, DomainAuthorization authorization) {
        this.projects = projects;
        this.accesses = accesses;
        this.activities = activities;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** Null fields mean "not sent": on update they keep the stored value. */
    public record ProjectInput(String name, String address, LocalDate startDate, LocalDate expectedEndDate,
                               ProjectStatus status) {}

    /** Counts are null when the caller cannot view this site's tasks. */
    public record ProjectData(UUID id, String name, String address, LocalDate startDate, LocalDate expectedEndDate,
                              ProjectStatus status, Long activityCount, Long doneCount) {}

    @Transactional
    public ProjectData create(UUID userId, UUID organizationId, ProjectInput input) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PROJECT);
        Project project = new Project(member.getOrganization(), input.name());
        project.describe(input.address(), input.startDate(), input.expectedEndDate());
        if (input.status() != null) {
            project.changeStatus(input.status());
        }
        return withCounts(userId, organizationId, member, List.of(projects.save(project))).get(0);
    }

    @Transactional(readOnly = true)
    public List<ProjectData> list(UUID userId, UUID organizationId, ProjectStatus status) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        PermissionScope scope = authorization.scopeOf(userId, organizationId, PermissionAction.VIEW_PROJECT)
                .orElseThrow(() -> new AccessDeniedException("Permission required"));
        Set<ProjectStatus> statuses = status == null ? NOT_ARCHIVED : EnumSet.of(status);
        List<Project> visible = scope == PermissionScope.ORGANIZATION
                ? projects.findInOrganization(organizationId, statuses)
                : projects.findAccessibleInOrganization(organizationId, statuses, member.getId());
        return withCounts(userId, organizationId, member, visible);
    }

    @Transactional(readOnly = true)
    public ProjectData get(UUID userId, UUID organizationId, UUID projectId) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.VIEW_PROJECT);
        return withCounts(userId, organizationId, member, List.of(project)).get(0);
    }

    @Transactional
    public ProjectData update(UUID userId, UUID organizationId, UUID projectId, ProjectInput input) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        Project project = load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PROJECT);
        if (input.name() != null) {
            project.rename(input.name());
        }
        project.describe(input.address() != null ? input.address() : project.getAddress(),
                input.startDate() != null ? input.startDate() : project.getStartDate(),
                input.expectedEndDate() != null ? input.expectedEndDate() : project.getExpectedEndDate());
        if (input.status() != null) {
            project.changeStatus(input.status());
        }
        return withCounts(userId, organizationId, member, List.of(project)).get(0);
    }

    /** Used by the other project-module services to resolve a site inside the tenant. */
    public Project load(UUID organizationId, UUID projectId) {
        return projects.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    private List<ProjectData> withCounts(UUID userId, UUID organizationId, OrganizationMembership member,
                                         List<Project> list) {
        PermissionScope activityScope = authorization.scopeOf(userId, organizationId, PermissionAction.VIEW_ACTIVITY)
                .orElse(null);
        Set<UUID> covered = new HashSet<>();
        if (activityScope == PermissionScope.ORGANIZATION) {
            list.forEach(p -> covered.add(p.getId()));
        } else if (activityScope == PermissionScope.PROJECT) {
            Set<UUID> assigned = accesses.findProjectIdsByMembership(member.getId());
            list.stream().map(Project::getId).filter(assigned::contains).forEach(covered::add);
        }
        Map<UUID, long[]> counts = new HashMap<>();
        if (!covered.isEmpty()) {
            for (Object[] row : activities.countByProject(covered)) {
                counts.put((UUID) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            }
        }
        return list.stream().map(p -> {
            boolean visible = covered.contains(p.getId());
            long[] c = counts.getOrDefault(p.getId(), new long[] {0, 0});
            return new ProjectData(p.getId(), p.getName(), p.getAddress(), p.getStartDate(), p.getExpectedEndDate(),
                    p.getStatus(), visible ? c[0] : null, visible ? c[1] : null);
        }).toList();
    }
}

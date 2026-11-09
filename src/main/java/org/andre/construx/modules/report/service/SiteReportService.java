package org.andre.construx.modules.report.service;

import org.andre.construx.modules.finance.model.Expense;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.repository.ExpenseRepository;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.project.model.*;
import org.andre.construx.modules.project.repository.ActivityRepository;
import org.andre.construx.modules.project.repository.GoalRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.service.ProjectService;
import org.andre.construx.modules.report.dto.SiteReportResponse;
import org.andre.construx.modules.report.model.SiteReport;
import org.andre.construx.modules.report.repository.SiteReportRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates, lists, and provides download URLs for Site Reports.
 * Requires MANAGE_PROJECT to generate; any active member of the site may list and download.
 */
@Service
public class SiteReportService {

    private static final Logger log = LoggerFactory.getLogger(SiteReportService.class);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);

    private final SiteReportRepository reports;
    private final ProjectService projectService;
    private final ExpenseRepository expenseRepository;
    private final GoalRepository goalRepository;
    private final ActivityRepository activityRepository;
    private final ProjectAccessRepository projectAccessRepository;
    private final PersonDirectory directory;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;
    private final ObjectStorage storage;
    private final PdfGenerationService pdfService;

    public SiteReportService(SiteReportRepository reports, ProjectService projectService,
                             ExpenseRepository expenseRepository, GoalRepository goalRepository,
                             ActivityRepository activityRepository,
                             ProjectAccessRepository projectAccessRepository,
                             PersonDirectory directory,
                             MembershipGuard guard, DomainAuthorization authorization,
                             ObjectStorage storage, PdfGenerationService pdfService) {
        this.reports = reports;
        this.projectService = projectService;
        this.expenseRepository = expenseRepository;
        this.goalRepository = goalRepository;
        this.activityRepository = activityRepository;
        this.projectAccessRepository = projectAccessRepository;
        this.directory = directory;
        this.guard = guard;
        this.authorization = authorization;
        this.storage = storage;
        this.pdfService = pdfService;
    }

    @Transactional
    public SiteReportResponse generate(UUID userId, UUID organizationId, UUID projectId, String title) {
        OrganizationMembership membership = guard.requireActive(userId, organizationId);
        Project project = projectService.load(organizationId, projectId);
        authorization.require(userId, organizationId, projectId, null, PermissionAction.MANAGE_PROJECT);

        SiteReport report = new SiteReport(project, membership, title);
        reports.save(report);

        PdfGenerationService.SiteReportData data = collectData(project, membership);

        byte[] pdf;
        try {
            pdf = pdfService.generatePdf(data);
        } catch (Exception e) {
            report.markFailed();
            throw new IllegalStateException("PDF generation failed for report " + report.getId(), e);
        }

        try {
            storage.put(report.getStorageKey(), pdf, "application/pdf");
        } catch (Exception e) {
            log.warn("S3 upload failed for report {} — PDF available in response only", report.getId(), e);
        }

        report.markReady(pdf.length);
        String pdfBase64 = Base64.getEncoder().encodeToString(pdf);
        return toResponse(report, displayName(membership), pdfBase64);
    }

    @Transactional(readOnly = true)
    public List<SiteReportResponse> list(UUID userId, UUID organizationId, UUID projectId) {
        guard.requireActive(userId, organizationId);
        Project project = projectService.load(organizationId, projectId);
        return reports.findByProjectIdOrderByCreatedAtDesc(project.getId()).stream()
                .map(r -> toResponse(r, generatedByName(r)))
                .toList();
    }

    @Transactional(readOnly = true)
    public URI getDownloadUrl(UUID userId, UUID organizationId, UUID projectId, UUID reportId) {
        guard.requireActive(userId, organizationId);
        Project project = projectService.load(organizationId, projectId);
        SiteReport report = reports.findByIdAndProjectId(reportId, project.getId())
                .filter(SiteReport::isReady)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found or not ready"));
        return storage.presignDownload(report.getStorageKey(), DOWNLOAD_TTL);
    }

    // ─── Data collection ─────────────────────────────────────────────────────

    private PdfGenerationService.SiteReportData collectData(Project project, OrganizationMembership requester) {
        UUID projectId = project.getId();

        // Expenses
        List<Expense> expenseList = expenseRepository.findByProjectIdOrderBySpentOnDescCreatedAtDesc(projectId);
        BigDecimal totalSpent = expenseList.stream().map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        Map<ExpenseCategory, BigDecimal> catTotals = new EnumMap<>(ExpenseCategory.class);
        for (Expense e : expenseList) {
            catTotals.merge(e.getCategory(), e.getAmount(), BigDecimal::add);
        }
        List<PdfGenerationService.CategoryTotal> byCategory = Arrays.stream(ExpenseCategory.values())
                .map(c -> new PdfGenerationService.CategoryTotal(c,
                        catTotals.getOrDefault(c, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)))
                .toList();

        BigDecimal budget = project.getAllocatedBudget();
        BigDecimal remaining = budget == null ? null : budget.subtract(totalSpent);

        List<PdfGenerationService.ExpenseRow> expenses = expenseList.stream()
                .map(e -> new PdfGenerationService.ExpenseRow(e.getSpentOn(), e.getDescription(),
                        e.getCategory(), e.getAmount()))
                .toList();

        // Phases + tasks — single pass to avoid loading activities twice
        List<Goal> goals = goalRepository.findByProjectIdOrderByDueAtAsc(projectId);
        Map<UUID, List<Activity>> activitiesByGoal = new LinkedHashMap<>();
        Map<UUID, UUID> taskMembershipToUser = new HashMap<>(); // membershipId → userAccountId
        for (Goal goal : goals) {
            List<Activity> acts = activityRepository.findByGoalWithTeam(goal.getId());
            activitiesByGoal.put(goal.getId(), acts);
            for (Activity a : acts) {
                for (ActivityAssignee t : a.getTeam()) {
                    taskMembershipToUser.put(
                            t.getAccess().getOrganizationMembership().getId(),
                            t.getAccess().getOrganizationMembership().getUserAccount().getId());
                }
            }
        }
        Map<UUID, String> profileNames = fetchProfileNames(taskMembershipToUser);

        List<PdfGenerationService.PhaseData> phases = goals.stream().map(goal -> {
            List<Activity> activities = activitiesByGoal.get(goal.getId());
            long done = activities.stream().filter(a -> a.getStatus() == ActivityStatus.DONE).count();
            List<PdfGenerationService.TaskRow> tasks = activities.stream().map(a -> {
                String leadName = a.getTeam().stream()
                        .filter(t -> t.getTaskRole() == TaskRole.LEAD)
                        .findFirst()
                        .map(t -> profileNames.getOrDefault(
                                t.getAccess().getOrganizationMembership().getId(),
                                t.getAccess().getOrganizationMembership().getUserAccount().getEmail()))
                        .orElse(null);
                return new PdfGenerationService.TaskRow(
                        a.getName(), a.getStatus(), leadName,
                        a.getStartAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        a.getDueAt().atZone(ZoneOffset.UTC).toLocalDate());
            }).toList();
            return new PdfGenerationService.PhaseData(
                    goal.getName(),
                    goal.getStartAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    goal.getDueAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    activities.size(), (int) done, tasks);
        }).toList();

        // Team
        List<ProjectAccess> teamAccess = projectAccessRepository.findActiveByProject(projectId);
        List<UUID> teamMembershipIds = teamAccess.stream()
                .map(a -> a.getOrganizationMembership().getId()).toList();
        Map<UUID, String> teamProfiles = loadProfiles(teamMembershipIds, teamAccess);

        List<PdfGenerationService.TeamMember> team = teamAccess.stream()
                .map(a -> new PdfGenerationService.TeamMember(
                        teamProfiles.getOrDefault(a.getOrganizationMembership().getId(),
                                a.getOrganizationMembership().getUserAccount().getEmail()),
                        a.getOrganizationMembership().getRole().getName()))
                .toList();

        return new PdfGenerationService.SiteReportData(
                project.getName() + " — Informe",
                project.getName(),
                project.getOrganization().getName(),
                project.getAddress(),
                project.getStartDate(),
                project.getExpectedEndDate(),
                project.getStatus(),
                java.time.Instant.now(),
                displayName(requester),
                budget,
                totalSpent,
                remaining,
                byCategory,
                expenses,
                phases,
                team);
    }

    /** Maps membershipId → display name using (membershipId → userAccountId) for the profile lookup. */
    private Map<UUID, String> fetchProfileNames(Map<UUID, UUID> membershipToUser) {
        if (membershipToUser.isEmpty()) return Map.of();
        List<UUID> userIds = new ArrayList<>(membershipToUser.values());
        Map<UUID, Person> people = directory.byUserId(userIds);
        Map<UUID, String> result = new HashMap<>();
        for (var entry : membershipToUser.entrySet()) {
            String name = PersonDirectory.of(people, entry.getValue()).fullName();
            if (name != null) result.put(entry.getKey(), name);
        }
        return result;
    }

    private Map<UUID, String> loadProfiles(List<UUID> membershipIds, List<ProjectAccess> access) {
        if (membershipIds.isEmpty()) return Map.of();
        Map<UUID, UUID> membershipToUser = access.stream().collect(Collectors.toMap(
                a -> a.getOrganizationMembership().getId(),
                a -> a.getOrganizationMembership().getUserAccount().getId()));
        List<UUID> userIds = new ArrayList<>(membershipToUser.values());
        Map<UUID, Person> people = directory.byUserId(userIds);
        Map<UUID, String> result = new HashMap<>();
        for (var entry : membershipToUser.entrySet()) {
            String name = PersonDirectory.of(people, entry.getValue()).fullName();
            if (name != null) result.put(entry.getKey(), name);
        }
        return result;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String displayName(OrganizationMembership m) {
        String fullName = directory.of(m.getUserAccount().getId()).fullName();
        return fullName != null ? fullName : m.getUserAccount().getEmail();
    }

    private String generatedByName(SiteReport report) {
        if (report.getGeneratedBy() == null) return "—";
        return displayName(report.getGeneratedBy());
    }

    private SiteReportResponse toResponse(SiteReport report, String generatedByName) {
        return new SiteReportResponse(
                report.getId(), report.getTitle(), report.getStatus().name(),
                generatedByName, report.getCreatedAt(), report.getCompletedAt(), report.getSizeBytes(), null);
    }

    private SiteReportResponse toResponse(SiteReport report, String generatedByName, String pdfBase64) {
        return new SiteReportResponse(
                report.getId(), report.getTitle(), report.getStatus().name(),
                generatedByName, report.getCreatedAt(), report.getCompletedAt(), report.getSizeBytes(), pdfBase64);
    }
}

package org.andre.construx.modules.report.service;

import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.finance.service.ExpenseService;
import org.andre.construx.modules.finance.service.ExpenseService.ExpenseData;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.Project;
import org.andre.construx.modules.project.service.ActivityService;
import org.andre.construx.modules.project.service.ActivityService.PhaseOverview;
import org.andre.construx.modules.project.service.ActivityService.TaskOverview;
import org.andre.construx.modules.project.service.ProjectAccessService;
import org.andre.construx.modules.project.service.ProjectAccessService.AccessData;
import org.andre.construx.modules.report.dto.SiteReportResponse;
import org.andre.construx.modules.report.model.SiteReport;
import org.andre.construx.modules.report.repository.SiteReportRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
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
import java.util.stream.Stream;

/**
 * Generates, lists, and provides download URLs for Site Reports.
 * Requires MANAGE_PROJECT to generate; any active member of the site may list and download.
 */
@Service
public class SiteReportService {

    private static final Logger log = LoggerFactory.getLogger(SiteReportService.class);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);

    private final SiteReportRepository reports;
    private final ExpenseService expenseService;
    private final ActivityService activityService;
    private final ProjectAccessService accessService;
    private final MembershipDirectory memberships;
    private final PersonDirectory directory;
    private final ObjectStorage storage;
    private final PdfGenerationService pdfService;

    public SiteReportService(SiteReportRepository reports, ExpenseService expenseService,
                             ActivityService activityService, ProjectAccessService accessService,
                             MembershipDirectory memberships, PersonDirectory directory,
                             ObjectStorage storage, PdfGenerationService pdfService) {
        this.reports = reports;
        this.expenseService = expenseService;
        this.activityService = activityService;
        this.accessService = accessService;
        this.memberships = memberships;
        this.directory = directory;
        this.storage = storage;
        this.pdfService = pdfService;
    }

    @Transactional
    public SiteReportResponse generate(SiteScope site, String title) {
        site.require(PermissionAction.MANAGE_PROJECT);
        OrganizationMembership membership = site.membership();

        SiteReport report = new SiteReport(site.site(), membership, title);
        reports.save(report);

        PdfGenerationService.SiteReportData data = collectData(site.site(), membership);

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
    public List<SiteReportResponse> list(SiteScope site) {
        return reports.findByProjectIdOrderByCreatedAtDesc(site.siteId()).stream()
                .map(r -> toResponse(r, generatedByName(r)))
                .toList();
    }

    @Transactional(readOnly = true)
    public URI getDownloadUrl(SiteScope site, UUID reportId) {
        SiteReport report = reports.findByIdAndProjectId(reportId, site.siteId())
                .filter(SiteReport::isReady)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found or not ready"));
        return storage.presignDownload(report.getStorageKey(), DOWNLOAD_TTL);
    }

    // ─── Data collection ─────────────────────────────────────────────────────

    private PdfGenerationService.SiteReportData collectData(Project project, OrganizationMembership requester) {
        UUID projectId = project.getId();

        // Expenses
        List<ExpenseData> expenseList = expenseService.ofSite(projectId);
        BigDecimal totalSpent = expenseList.stream().map(ExpenseData::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        Map<ExpenseCategory, BigDecimal> catTotals = new EnumMap<>(ExpenseCategory.class);
        for (ExpenseData e : expenseList) {
            catTotals.merge(e.category(), e.amount(), BigDecimal::add);
        }
        List<PdfGenerationService.CategoryTotal> byCategory = Arrays.stream(ExpenseCategory.values())
                .map(c -> new PdfGenerationService.CategoryTotal(c,
                        catTotals.getOrDefault(c, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)))
                .toList();

        BigDecimal budget = project.getAllocatedBudget();
        BigDecimal remaining = budget == null ? null : budget.subtract(totalSpent);

        List<PdfGenerationService.ExpenseRow> expenses = expenseList.stream()
                .map(e -> new PdfGenerationService.ExpenseRow(e.spentOn(), e.description(), e.category(), e.amount()))
                .toList();

        // Phases + tasks; a Lead without a profile name shows their email
        List<PhaseOverview> overview = activityService.overviewOf(projectId);
        List<AccessData> staff = accessService.staff(projectId);
        Map<UUID, String> emails = emailsOf(overview.stream().flatMap(p -> p.tasks().stream())
                .filter(t -> t.leadMembershipId() != null && t.leadName() == null)
                .map(TaskOverview::leadMembershipId), staff.stream()
                .filter(a -> a.fullName() == null).map(AccessData::membershipId));

        List<PdfGenerationService.PhaseData> phases = overview.stream().map(phase -> {
            long done = phase.tasks().stream().filter(t -> t.status() == ActivityStatus.DONE).count();
            List<PdfGenerationService.TaskRow> tasks = phase.tasks().stream().map(t ->
                    new PdfGenerationService.TaskRow(
                            t.name(), t.status(),
                            t.leadMembershipId() == null ? null
                                    : t.leadName() != null ? t.leadName() : emails.get(t.leadMembershipId()),
                            t.startAt().atZone(ZoneOffset.UTC).toLocalDate(),
                            t.dueAt().atZone(ZoneOffset.UTC).toLocalDate())).toList();
            return new PdfGenerationService.PhaseData(
                    phase.name(),
                    phase.startAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    phase.dueAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    phase.tasks().size(), (int) done, tasks);
        }).toList();

        // Team
        List<PdfGenerationService.TeamMember> team = staff.stream()
                .map(a -> new PdfGenerationService.TeamMember(
                        a.fullName() != null ? a.fullName() : emails.get(a.membershipId()), a.roleName()))
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

    /** Email per membership, in one query, for the people the report has no name for. */
    private Map<UUID, String> emailsOf(Stream<UUID> leads, Stream<UUID> staff) {
        List<UUID> ids = Stream.concat(leads, staff).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> result = new HashMap<>();
        for (OrganizationMembership m : memberships.byIds(ids)) {
            result.put(m.getId(), m.getUserAccount().getEmail());
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

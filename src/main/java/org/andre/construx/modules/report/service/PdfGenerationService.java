package org.andre.construx.modules.report.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.andre.construx.modules.finance.model.ExpenseCategory;
import org.andre.construx.modules.project.model.ActivityStatus;
import org.andre.construx.modules.project.model.ProjectStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a Site Report PDF from pre-assembled data. All domain access is done by the caller;
 * this service is pure PDF rendering.
 */
@Service
public class PdfGenerationService {

    private static final Color COLOR_HEADER   = new Color(30, 58, 95);
    private static final Color COLOR_ROW_ALT  = new Color(245, 247, 250);
    private static final Color COLOR_WHITE    = Color.WHITE;
    private static final Color COLOR_BORDER   = new Color(200, 210, 220);
    private static final Color COLOR_OVERRUN  = new Color(200, 40, 40);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ─── Data records ────────────────────────────────────────────────────────

    public record CategoryTotal(ExpenseCategory category, BigDecimal amount) {}

    public record ExpenseRow(LocalDate spentOn, String description, ExpenseCategory category, BigDecimal amount) {}

    public record TaskRow(String name, ActivityStatus status, String leadName, LocalDate startAt, LocalDate dueAt) {}

    public record PhaseData(String name, LocalDate startAt, LocalDate dueAt, int totalTasks, int doneTasks,
                            List<TaskRow> tasks) {}

    public record TeamMember(String displayName, String roleName) {}

    public record SiteReportData(
            String reportTitle,
            String siteName,
            String organizationName,
            String address,
            LocalDate startDate,
            LocalDate expectedEndDate,
            ProjectStatus siteStatus,
            Instant generatedAt,
            String generatedByName,
            // Finance
            BigDecimal allocatedBudget,
            BigDecimal spent,
            BigDecimal remaining,
            List<CategoryTotal> byCategory,
            // Expenses
            List<ExpenseRow> expenses,
            // Phases
            List<PhaseData> phases,
            // Team
            List<TeamMember> team) {}

    // ─── Public API ──────────────────────────────────────────────────────────

    public byte[] generatePdf(SiteReportData data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40f, 40f, 60f, 40f);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            addCoverPage(doc, data);
            doc.newPage();
            addFinancialSummary(doc, data);
            addExpenseSection(doc, data);
            addPhaseSection(doc, data);
            addTeamSection(doc, data);
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF generation failed", e);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    // ─── Sections ────────────────────────────────────────────────────────────

    private void addCoverPage(Document doc, SiteReportData data) throws DocumentException {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24f, COLOR_HEADER);
        Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14f, COLOR_HEADER);
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f, Color.DARK_GRAY);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10f, Color.BLACK);

        doc.add(spacer(30f));
        Paragraph title = new Paragraph(data.reportTitle(), titleFont);
        title.setAlignment(Element.ALIGN_LEFT);
        doc.add(title);

        doc.add(spacer(6f));
        doc.add(new Paragraph(data.siteName(), headingFont));
        doc.add(spacer(20f));

        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(100f);
        meta.setWidths(new float[]{1f, 2f});
        meta.setSpacingAfter(20f);

        addMetaRow(meta, "Organización", data.organizationName(), labelFont, valueFont);
        addMetaRow(meta, "Dirección", nvl(data.address()), labelFont, valueFont);
        addMetaRow(meta, "Inicio", fmtDate(data.startDate()), labelFont, valueFont);
        addMetaRow(meta, "Cierre previsto", fmtDate(data.expectedEndDate()), labelFont, valueFont);
        addMetaRow(meta, "Estado", statusLabel(data.siteStatus()), labelFont, valueFont);
        addMetaRow(meta, "Generado el", fmtInstant(data.generatedAt()), labelFont, valueFont);
        addMetaRow(meta, "Generado por", data.generatedByName(), labelFont, valueFont);
        doc.add(meta);
    }

    private void addFinancialSummary(Document doc, SiteReportData data) throws DocumentException {
        addSectionHeader(doc, "Resumen Financiero");

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f, COLOR_WHITE);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 9f, Color.BLACK);

        // Top summary table
        PdfPTable summary = new PdfPTable(3);
        summary.setWidthPercentage(100f);
        summary.setSpacingAfter(12f);

        addHeaderCell(summary, "Presupuesto asignado", labelFont);
        addHeaderCell(summary, "Total gastado", labelFont);
        addHeaderCell(summary, "Restante", labelFont);

        boolean overrun = data.remaining() != null && data.remaining().compareTo(BigDecimal.ZERO) < 0;
        Font remainFont = overrun ? FontFactory.getFont(FontFactory.HELVETICA, 9f, COLOR_OVERRUN) : valueFont;

        addDataCell(summary, money(data.allocatedBudget()), valueFont, 0);
        addDataCell(summary, money(data.spent()), valueFont, 0);
        addDataCell(summary, data.remaining() == null ? "—" : money(data.remaining()), remainFont, 0);
        doc.add(summary);

        if (data.byCategory() == null || data.byCategory().isEmpty()) return;

        // By-category table
        PdfPTable cats = new PdfPTable(3);
        cats.setWidthPercentage(70f);
        cats.setHorizontalAlignment(Element.ALIGN_LEFT);
        cats.setSpacingAfter(20f);

        addHeaderCell(cats, "Categoría", labelFont);
        addHeaderCell(cats, "Monto", labelFont);
        addHeaderCell(cats, "% del gasto", labelFont);

        BigDecimal totalSpent = data.spent() == null || data.spent().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE : data.spent();

        int row = 0;
        for (CategoryTotal ct : data.byCategory()) {
            Color bg = (row++ % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;
            addDataCell(cats, categoryLabel(ct.category()), valueFont, bg);
            addDataCell(cats, money(ct.amount()), valueFont, bg);
            String pct = ct.amount().multiply(BigDecimal.valueOf(100))
                    .divide(totalSpent, 1, RoundingMode.HALF_UP) + " %";
            addDataCell(cats, pct, valueFont, bg);
        }
        doc.add(cats);
    }

    private void addExpenseSection(Document doc, SiteReportData data) throws DocumentException {
        if (data.expenses() == null || data.expenses().isEmpty()) return;

        addSectionHeader(doc, "Lista de Gastos");

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f, COLOR_WHITE);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 8f, Color.BLACK);

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{1.2f, 3f, 1.5f, 1.3f});
        table.setSpacingAfter(20f);

        addHeaderCell(table, "Fecha", labelFont);
        addHeaderCell(table, "Descripción", labelFont);
        addHeaderCell(table, "Categoría", labelFont);
        addHeaderCell(table, "Monto", labelFont);

        int row = 0;
        for (ExpenseRow e : data.expenses()) {
            Color bg = (row++ % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;
            addDataCell(table, fmtDate(e.spentOn()), valueFont, bg);
            addDataCell(table, e.description(), valueFont, bg);
            addDataCell(table, categoryLabel(e.category()), valueFont, bg);
            addDataCell(table, money(e.amount()), valueFont, bg);
        }
        doc.add(table);
    }

    private void addPhaseSection(Document doc, SiteReportData data) throws DocumentException {
        if (data.phases() == null || data.phases().isEmpty()) return;

        addSectionHeader(doc, "Avance por Fases");

        Font phaseFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f, COLOR_HEADER);
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f, COLOR_WHITE);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 8f, Color.BLACK);

        for (PhaseData phase : data.phases()) {
            Paragraph phaseName = new Paragraph(phase.name(), phaseFont);
            phaseName.setSpacingBefore(8f);
            phaseName.setSpacingAfter(2f);
            doc.add(phaseName);

            String progress = "%d / %d tareas completadas".formatted(phase.doneTasks(), phase.totalTasks());
            if (phase.totalTasks() > 0) {
                int pct = phase.doneTasks() * 100 / phase.totalTasks();
                progress += " (%d%%)".formatted(pct);
            }
            Font progFont = FontFactory.getFont(FontFactory.HELVETICA, 8f, Color.DARK_GRAY);
            Paragraph progPara = new Paragraph(progress, progFont);
            progPara.setSpacingAfter(4f);
            doc.add(progPara);

            if (phase.tasks() == null || phase.tasks().isEmpty()) continue;

            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100f);
            table.setWidths(new float[]{3f, 1.5f, 2f, 1.2f, 1.2f});
            table.setSpacingAfter(12f);

            addHeaderCell(table, "Tarea", labelFont);
            addHeaderCell(table, "Estado", labelFont);
            addHeaderCell(table, "Responsable", labelFont);
            addHeaderCell(table, "Inicio", labelFont);
            addHeaderCell(table, "Fin", labelFont);

            int row = 0;
            for (TaskRow t : phase.tasks()) {
                Color bg = (row++ % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;
                addDataCell(table, t.name(), valueFont, bg);
                addDataCell(table, statusLabel(t.status()), valueFont, bg);
                addDataCell(table, nvl(t.leadName()), valueFont, bg);
                addDataCell(table, fmtDate(t.startAt()), valueFont, bg);
                addDataCell(table, fmtDate(t.dueAt()), valueFont, bg);
            }
            doc.add(table);
        }
    }

    private void addTeamSection(Document doc, SiteReportData data) throws DocumentException {
        if (data.team() == null || data.team().isEmpty()) return;

        addSectionHeader(doc, "Equipo");

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f, COLOR_WHITE);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 9f, Color.BLACK);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(70f);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingAfter(20f);

        addHeaderCell(table, "Miembro", labelFont);
        addHeaderCell(table, "Rol", labelFont);

        int row = 0;
        for (TeamMember m : data.team()) {
            Color bg = (row++ % 2 == 0) ? COLOR_WHITE : COLOR_ROW_ALT;
            addDataCell(table, m.displayName(), valueFont, bg);
            addDataCell(table, m.roleName(), valueFont, bg);
        }
        doc.add(table);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void addSectionHeader(Document doc, String text) throws DocumentException {
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12f, COLOR_WHITE);
        PdfPTable header = new PdfPTable(1);
        header.setWidthPercentage(100f);
        header.setSpacingBefore(16f);
        header.setSpacingAfter(8f);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(COLOR_HEADER);
        cell.setPadding(6f);
        cell.setBorder(Rectangle.NO_BORDER);
        header.addCell(cell);
        doc.add(header);
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(COLOR_HEADER);
        cell.setPadding(5f);
        cell.setBorderColor(COLOR_BORDER);
        cell.setBorderWidth(0.5f);
        table.addCell(cell);
    }

    private void addDataCell(PdfPTable table, String text, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "—" : text, font));
        if (bg != null) cell.setBackgroundColor(bg);
        cell.setPadding(4f);
        cell.setBorderColor(COLOR_BORDER);
        cell.setBorderWidth(0.5f);
        table.addCell(cell);
    }

    private void addDataCell(PdfPTable table, String text, Font font, int unused) {
        addDataCell(table, text, font, (Color) null);
    }

    private void addMetaRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell lc = new PdfPCell(new Phrase(label, labelFont));
        lc.setBorder(Rectangle.BOTTOM);
        lc.setBorderColor(COLOR_BORDER);
        lc.setPadding(4f);
        table.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(value, valueFont));
        vc.setBorder(Rectangle.BOTTOM);
        vc.setBorderColor(COLOR_BORDER);
        vc.setPadding(4f);
        table.addCell(vc);
    }

    private Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(height);
        return p;
    }

    private String fmtDate(LocalDate d) {
        return d == null ? "—" : d.format(DATE_FMT);
    }

    private String fmtInstant(Instant i) {
        return i == null ? "—" : i.atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT);
    }

    private String money(BigDecimal v) {
        if (v == null) return "—";
        return "$ " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private String categoryLabel(ExpenseCategory c) {
        if (c == null) return "—";
        return switch (c) {
            case MATERIALS -> "Materiales";
            case LABOR     -> "Mano de obra";
            case EQUIPMENT -> "Equipos";
            case OTHER     -> "Otros";
        };
    }

    private String statusLabel(ProjectStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case PLANNED   -> "Planificado";
            case ACTIVE    -> "Activo";
            case ON_HOLD   -> "En pausa";
            case COMPLETED -> "Completado";
            case ARCHIVED  -> "Archivado";
        };
    }

    private String statusLabel(ActivityStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case TODO        -> "Pendiente";
            case IN_PROGRESS -> "En progreso";
            case DONE        -> "Completado";
        };
    }
}

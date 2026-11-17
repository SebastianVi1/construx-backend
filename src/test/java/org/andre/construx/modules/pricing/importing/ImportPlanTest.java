package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingResource;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading and planning a Presupuesto workbook (docs/pricing.md §4.5): failures, warnings and the merge. */
class ImportPlanTest {
    @TempDir Path dir;

    private ImportPlan plan(SyntheticPresupuesto wb, CatalogSnapshot snapshot) {
        return ImportPlan.of(PresupuestoFile.read(wb.file(dir).toFile()), snapshot);
    }

    private ImportPlan plan(SyntheticPresupuesto wb) {
        return plan(wb, CatalogSnapshot.empty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> counts(ImportPlan plan, String type) {
        return map(map(plan.summary.get("resources")).get(type));
    }

    @Test
    void plansTheWholeWorkbookAsNewAgainstAnEmptyCatalog() {
        ImportPlan plan = plan(SyntheticPresupuesto.standard());

        assertThat(counts(plan, "MATERIAL")).containsEntry("new", 4).containsEntry("costChanged", 0);
        assertThat(counts(plan, "LABOR")).containsEntry("new", 3);
        assertThat(counts(plan, "EQUIPMENT")).containsEntry("new", 1);
        assertThat(counts(plan, "WORK_ITEM")).containsEntry("new", 1);
        assertThat(map(plan.summary.get("units"))).containsEntry("new", 6).containsEntry("unchanged", 0);
        assertThat(map(plan.summary.get("families"))).containsEntry("new", 2);
        assertThat(map(plan.summary.get("suppliers"))).containsEntry("new", 1);
        assertThat(map(plan.summary.get("rows"))).containsEntry("CatalogoGeneral", 9).containsEntry("Matrices", 7)
                .containsEntry("DetalleManoObra", 2).containsEntry("DetalleMaquinaria", 1)
                .containsEntry("OperacionCH", 1).containsEntry("ConsumosCH", 1);
        assertThat(map(plan.summary.get("fasar"))).containsEntry("provided", false)
                .containsEntry("minimumWage", "248.93").containsEntry("laborCategories", 2);
        assertThat(plan.composites).containsExactlyInAnyOrder("1A", "MORT-1:4", "10301-001");
        assertThat(plan.resourceIds).hasSize(9).doesNotContainKey("NOT-A-CODE");
        // the file costs agree with the analyses: no cost mismatch
        assertThat(plan.warnings().count("WORK_ITEM_COST_MISMATCH")).isZero();
        assertThat(plan.warnings().count("LABOR_COST_MISMATCH")).isZero();
        assertThat(plan.warnings().count("UNIT_NOT_LISTED")).isEqualTo(1);
    }

    @Test
    void generalFieldsAboveOneAreTheHundredfoldPercentBug() {
        ImportPlan plan = plan(SyntheticPresupuesto.standard());

        PresupuestoFile.GeneralFields fields = plan.generalFields();
        assertThat(fields.minimumWage()).isEqualByComparingTo("248.93");
        assertThat(fields.retirement()).isEqualByComparingTo("0.02");
        assertThat(fields.infonavit()).isEqualByComparingTo("0.05");
        assertThat(fields.payrollTax()).isEqualByComparingTo("0.03");
        assertThat(fields.risk()).isEqualByComparingTo("0.0758875");
        assertThat(fields.otherChargesPct()).isEqualByComparingTo("0");
        assertThat(fields.sumEmployerAndWorker()).isTrue();
        assertThat(plan.warnings().count("GENERAL_FIELDS_PERCENT_SCALE")).isEqualTo(3);
    }

    private void fails(Consumer<SyntheticPresupuesto> change, String code, String sheet, Integer row,
                       String column) {
        SyntheticPresupuesto wb = SyntheticPresupuesto.standard();
        change.accept(wb);
        assertThatThrownBy(() -> plan(wb)).isInstanceOfSatisfying(ImportFailedException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getSheet()).isEqualTo(sheet);
            assertThat(ex.getRow()).isEqualTo(row);
            assertThat(ex.getColumn()).isEqualTo(column);
            assertThat(ex.getMessage()).isNotBlank();
        });
    }

    @Test
    void anUnreadableFileFails() throws Exception {
        Path junk = Files.writeString(dir.resolve("junk.xlsx"), "not a workbook");
        assertThatThrownBy(() -> PresupuestoFile.read(junk.toFile()))
                .isInstanceOfSatisfying(ImportFailedException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKBOOK_UNREADABLE"));
    }

    @Test
    void structuralFailuresNameTheSheetAndColumn() {
        fails(wb -> wb.without("Matrices"), "MISSING_SHEET", "Matrices", null, null);
        fails(wb -> wb.without("N_Campos Generales"), "MISSING_SHEET", "N_Campos Generales", null, null);
        fails(wb -> wb.sheet("Matrices").dropColumn("CodigoI"), "MISSING_COLUMN", "Matrices", 1, "CodigoI");
        fails(wb -> wb.sheet("CatalogoGeneral").dropColumn("Fecha0"), "MISSING_COLUMN", "CatalogoGeneral", 1,
                "Fecha0");
    }

    @Test
    void catalogRowFailuresNameTheCell() {
        // data rows start on row 2: 301-ARE-0101 is row 2, 301-CEM-0001 row 3, ...
        fails(wb -> wb.sheet("CatalogoGeneral").row("301-are-0101 ", "x", "x", "M3", 1), "DUPLICATE_CODE",
                "CatalogoGeneral", 11, "Codigo");
        fails(wb -> wb.sheet("CatalogoGeneral").row(null, "x", "Sin código", "M3", 1), "BLANK_CODE",
                "CatalogoGeneral", 11, "Codigo");
        fails(wb -> wb.sheet("CatalogoGeneral").set("MO011", "Tipo", 5), "UNSUPPORTED_TYPE", "CatalogoGeneral", 4,
                "Tipo");
        fails(wb -> wb.sheet("CatalogoGeneral").set("MO011", "Unidad", " "), "BLANK_UNIT", "CatalogoGeneral", 4,
                "Unidad");
        fails(wb -> wb.sheet("CatalogoGeneral").set("MO011", "Costo0", "caro"), "INVALID_NUMBER",
                "CatalogoGeneral", 4, "Costo0");
        fails(wb -> wb.sheet("CatalogoGeneral").set("MO011", "Costo0", -1), "INVALID_NUMBER", "CatalogoGeneral", 4,
                "Costo0");
        fails(wb -> wb.sheet("CatalogoGeneral").set("MO011", "Fecha0", "31/31/2026"), "INVALID_DATE",
                "CatalogoGeneral", 4, "Fecha0");
        fails(wb -> wb.sheet("CatalogoGeneral").set("%MO1", "Tipo %", 1), "UNSUPPORTED_PERCENTAGE_BASE",
                "CatalogoGeneral", 7, "Tipo %");
    }

    @Test
    void analysisAndDetailFailuresNameTheCell() {
        fails(wb -> wb.sheet("Matrices").set("1A", "CodigoI", "NOPE"), "UNKNOWN_CODE", "Matrices", 2, "CodigoI");
        fails(wb -> wb.sheet("DetalleManoObra").set("MO011", "Codigo", "MO999"), "UNKNOWN_CODE", "DetalleManoObra",
                2, "Codigo");
        fails(wb -> wb.sheet("OperacionCH").set("EQ001", "CodigoI", "NOPE"), "UNKNOWN_CODE", "OperacionCH", 2,
                "CodigoI");
        fails(wb -> wb.sheet("Matrices").set("1A", "Cantidad", 0), "INVALID_NUMBER", "Matrices", 2, "Cantidad");
        fails(wb -> wb.sheet("Matrices").set("1A", "/", "+"), "INVALID_OPERATOR", "Matrices", 2, "/");
        fails(wb -> wb.sheet("N_Campos Generales").set("primeramoneda", "VALOR", "DOLARES"), "UNSUPPORTED_CURRENCY",
                "N_Campos Generales", 5, "VALOR");
    }

    @Test
    void aCycleThroughTheFileIsFoundOnItsLine() {
        SyntheticPresupuesto wb = SyntheticPresupuesto.standard();
        wb.sheet("Matrices").row("MO011", "1A", 10, "*", 1, null);
        assertThatThrownBy(() -> plan(wb)).isInstanceOfSatisfying(ImportFailedException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo("ANALYSIS_CYCLE");
            assertThat(ex.getRow()).isNotNull();
        });
    }

    @Test
    void tooManyRowsFails() {
        SyntheticPresupuesto wb = SyntheticPresupuesto.standard();
        // the real limits are far above a test workbook, so the reader is given a small one
        Map<String, Integer> limits = Map.of("Matrices", 3);
        assertThatThrownBy(() -> XlsxSheets.read(wb.file(dir).toFile(), java.util.Set.of("Matrices"), limits))
                .isInstanceOfSatisfying(ImportFailedException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("TOO_MANY_ROWS");
                    assertThat(ex.getSheet()).isEqualTo("Matrices");
                });
    }

    @Test
    void cellWarningsAreCountedWithSamples() {
        SyntheticPresupuesto wb = SyntheticPresupuesto.standard();
        wb.sheet("CatalogoGeneral").set("301-ARE-0101", "Costo0", 290.004)
                .set("301-ARE-0101", "Familia", "999")
                .set("301-CEM-0001", "Proveedor", "P9")
                .set("EQ001", "Descripcion", "R".repeat(170))
                .set("10301-001", "Costo0", 150);
        wb.sheet("Matrices").set("MORT-1:4", "Cantidad", 1.1000001);
        wb.sheet("DetalleManoObra").set("MO020", "SalarioRealM0", 880);

        ImportPlan plan = plan(wb);

        ImportWarnings warnings = plan.warnings();
        assertThat(warnings.count("COST_ROUNDED")).isEqualTo(1);
        assertThat(warnings.count("UNKNOWN_FAMILY")).isEqualTo(1);
        assertThat(warnings.count("UNKNOWN_SUPPLIER")).isEqualTo(1);
        assertThat(warnings.count("NAME_TRUNCATED")).isEqualTo(1);
        assertThat(warnings.count("QUANTITY_ROUNDED")).isEqualTo(1);
        assertThat(warnings.count("WORK_ITEM_COST_MISMATCH")).isEqualTo(1);
        assertThat(warnings.count("LABOR_COST_MISMATCH")).isEqualTo(1);
        assertThat(plan.resourceFamily).doesNotContainKey("301-ARE-0101");
        Map<String, Object> summary = map(plan.summary.get("warnings"));
        assertThat(summary.get("total")).isEqualTo(warnings.total());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sample = (List<Map<String, Object>>) summary.get("sample");
        assertThat(sample).anySatisfy(s -> {
            assertThat(s).containsEntry("code", "WORK_ITEM_COST_MISMATCH").containsEntry("resourceCode", "10301-001")
                    .containsEntry("sheet", "CatalogoGeneral").containsEntry("row", 10);
            assertThat(map(s.get("detail"))).containsEntry("imported", "150.00").containsEntry("derived", "138.01");
        });
    }

    @Test
    void classifiesAgainstTheCurrentCatalog() {
        Map<String, ExistingResource> existing = new HashMap<>();
        existing.put("301-ARE-0101", resource("301-ARE-0101", ResourceType.MATERIAL, false, "290.00",
                CatalogSource.NEODATA_CONSTRUBASE));
        existing.put("301-CEM-0001", resource("301-CEM-0001", ResourceType.MATERIAL, false, "200.00",
                CatalogSource.MANUAL));
        existing.put("MORT-1:4", resource("MORT-1:4", ResourceType.MATERIAL, true, "2000.00",
                CatalogSource.NEODATA_CONSTRUBASE));
        existing.put("10301-001", resource("10301-001", ResourceType.WORK_ITEM, true, "138.01",
                CatalogSource.NEODATA_CONSTRUBASE));
        existing.put("OLD-1", resource("OLD-1", ResourceType.EQUIPMENT, false, "1.00",
                CatalogSource.NEODATA_CONSTRUBASE));
        existing.put("MAN-1", resource("MAN-1", ResourceType.MATERIAL, false, "1.00", CatalogSource.MANUAL));
        CatalogSnapshot snapshot = new CatalogSnapshot(Map.of("M3", UUID.randomUUID()), Map.of(), Map.of(),
                existing, List.of());

        ImportPlan plan = plan(SyntheticPresupuesto.standard(), snapshot);

        assertThat(counts(plan, "MATERIAL")).containsEntry("new", 1).containsEntry("costChanged", 2)
                .containsEntry("unchanged", 1).containsEntry("missingFromFile", 0);
        assertThat(counts(plan, "WORK_ITEM")).containsEntry("unchanged", 1);
        assertThat(counts(plan, "EQUIPMENT")).containsEntry("new", 1).containsEntry("missingFromFile", 1);
        assertThat(map(plan.summary.get("units"))).containsEntry("new", 5).containsEntry("unchanged", 1);
        assertThat(plan.resourceIds.get("301-ARE-0101")).isEqualTo(existing.get("301-ARE-0101").id());
        assertThat(plan.missing).extracting(ExistingResource::code).containsExactly("OLD-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) map(plan.summary.get("changes")).get("sample");
        assertThat(changes).anySatisfy(c -> assertThat(c).containsEntry("resourceCode", "301-CEM-0001")
                .containsEntry("from", "200.00").containsEntry("to", "245.50"));
        assertThat(changes).anySatisfy(c -> assertThat(c).containsEntry("resourceCode", "MORT-1:4")
                .containsEntry("from", "2000.00").containsEntry("to", "2037.50"));
    }

    private static ExistingResource resource(String code, ResourceType type, boolean composite, String cost,
                                             CatalogSource source) {
        return new ExistingResource(UUID.randomUUID(), code, type, code, composite, false, new BigDecimal(cost),
                Instant.parse("2026-01-01T00:00:00Z"), source);
    }
}

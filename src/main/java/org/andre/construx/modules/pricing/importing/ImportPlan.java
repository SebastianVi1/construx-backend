package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingLine;
import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingResource;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileFamily;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileLabor;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileLine;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileResource;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileSupplier;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileUnit;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.DerivedCost;
import org.andre.construx.modules.pricing.model.ResourceType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The outcome of importing a Presupuesto workbook into a Catalog, worked out without writing anything
 * (docs/pricing.md §4.5): every code resolved to the id it keeps or gets, the analyses checked for cycles, the
 * derived costs computed in memory, the merge classification and the dry-run summary. The write (§4.6) applies
 * exactly this plan.
 */
public final class ImportPlan {
    private static final BigDecimal LABOR_TOLERANCE = new BigDecimal("0.05");

    public final PresupuestoFile file;
    public final CatalogSnapshot snapshot;
    /** Every Resource code of the file to its existing or new id. */
    public final Map<String, UUID> resourceIds = new HashMap<>();
    /** Codes that are composite after the import. */
    public final Set<String> composites = new HashSet<>();
    /** Codes whose analysis lines the file replaces. */
    public final Set<String> analysisParents = new HashSet<>();
    /** One new id per {@link PresupuestoFile#lines} entry, in the same order. */
    public final List<UUID> lineIds = new ArrayList<>();
    public final Map<String, UUID> unitIds = new HashMap<>();
    /** Units of the sheet plus the ones only used by resource rows ({@code UNIT_NOT_LISTED}, no description). */
    public final List<FileUnit> units = new ArrayList<>();
    public final Map<String, UUID> familyIds = new HashMap<>();
    public final Map<String, UUID> supplierIds = new HashMap<>();
    /** Resource code to its Family / Supplier id, absent when it has none. */
    public final Map<String, UUID> resourceFamily = new HashMap<>();
    public final Map<String, UUID> resourceSupplier = new HashMap<>();
    /** Imported Resources of the Catalog absent from the file. */
    public final List<ExistingResource> missing = new ArrayList<>();
    /** Detail rows of simple Labor categories. */
    public final List<FileLabor> labor = new ArrayList<>();
    public final Map<String, Object> summary = new LinkedHashMap<>();

    private ImportPlan(PresupuestoFile file, CatalogSnapshot snapshot) {
        this.file = file;
        this.snapshot = snapshot;
    }

    public ImportWarnings warnings() {
        return file.warnings;
    }

    public List<PresupuestoFile.FileResource> resources() {
        return List.copyOf(file.resources.values());
    }

    public List<FileLine> lines() {
        return file.lines;
    }

    public List<FileSupplier> suppliers() {
        return file.suppliers;
    }

    public List<FileFamily> families() {
        return file.families;
    }

    public List<PresupuestoFile.FileEquipment> equipment() {
        return file.equipment;
    }

    public List<PresupuestoFile.FileEquipmentLine> operationLines() {
        return file.operationLines;
    }

    public List<PresupuestoFile.FileEquipmentLine> consumptionLines() {
        return file.consumptionLines;
    }

    public List<PresupuestoFile.FileStandby> standby() {
        return file.standby;
    }

    public PresupuestoFile.GeneralFields generalFields() {
        return file.generalFields;
    }

    /** Plans the import; an analysis cycle fails with {@code ANALYSIS_CYCLE}. */
    public static ImportPlan of(PresupuestoFile file, CatalogSnapshot snapshot) {
        ImportPlan plan = new ImportPlan(file, snapshot);
        plan.resolveUnits();
        plan.resolveFamiliesAndSuppliers();
        plan.resolveResources();
        plan.checkCycles();
        Map<String, BigDecimal> derived = plan.derive();
        plan.checkLabor();
        plan.summarize(derived);
        return plan;
    }

    private void resolveUnits() {
        unitIds.putAll(snapshot.units());
        Set<String> listed = new HashSet<>();
        for (FileUnit unit : file.units) {
            listed.add(unit.code());
            units.add(unit);
            unitIds.computeIfAbsent(unit.code(), k -> UUID.randomUUID());
        }
        for (FileResource r : file.resources.values()) {
            if (listed.add(r.unitCode())) {
                file.warnings.add("UNIT_NOT_LISTED", PresupuestoFile.CATALOG, r.row(), r.code(),
                        Map.of("unit", r.unitCode()));
                units.add(new FileUnit(r.unitCode(), null));
                unitIds.computeIfAbsent(r.unitCode(), k -> UUID.randomUUID());
            }
        }
    }

    private void resolveFamiliesAndSuppliers() {
        familyIds.putAll(snapshot.families());
        for (FileFamily family : file.families) {
            familyIds.computeIfAbsent(family.code(), k -> UUID.randomUUID());
        }
        snapshot.suppliersByCode().forEach((code, s) -> supplierIds.put(code, s.id()));
        for (FileSupplier supplier : file.suppliers) {
            supplierIds.computeIfAbsent(supplier.code(), k -> UUID.randomUUID());
        }
        for (FileResource r : file.resources.values()) {
            if (r.familyCode() != null) {
                UUID id = familyIds.get(r.familyCode());
                if (id == null) {
                    file.warnings.add("UNKNOWN_FAMILY", PresupuestoFile.CATALOG, r.row(), r.code(),
                            Map.of("family", r.familyCode()));
                } else {
                    resourceFamily.put(r.code(), id);
                }
            }
            if (r.supplierCode() != null) {
                UUID id = supplierIds.get(r.supplierCode());
                if (id == null) {
                    file.warnings.add("UNKNOWN_SUPPLIER", PresupuestoFile.CATALOG, r.row(), r.code(),
                            Map.of("supplier", r.supplierCode()));
                } else {
                    resourceSupplier.put(r.code(), id);
                }
            }
        }
    }

    private void resolveResources() {
        for (FileResource r : file.resources.values()) {
            ExistingResource existing = snapshot.resources().get(r.code());
            resourceIds.put(r.code(), existing == null ? UUID.randomUUID() : existing.id());
        }
        for (FileLine line : file.lines) {
            analysisParents.add(line.parentCode());
            lineIds.add(UUID.randomUUID());
        }
        composites.addAll(analysisParents);
        Map<UUID, String> codeById = codeById();
        for (ExistingLine line : keptLines()) {
            composites.add(codeById.get(line.parentId()));
        }
        for (ExistingResource r : snapshot.resources().values()) {
            if (!file.resources.containsKey(r.code()) && r.source() == CatalogSource.NEODATA_CONSTRUBASE) {
                missing.add(r);
            }
        }
        missing.sort((a, b) -> a.code().compareTo(b.code()));
    }

    /** Existing lines of parents the file does not re-analyse. */
    private List<ExistingLine> keptLines() {
        Map<UUID, String> codeById = codeById();
        List<ExistingLine> kept = new ArrayList<>();
        for (ExistingLine line : snapshot.lines()) {
            String parent = codeById.get(line.parentId());
            if (parent != null && !analysisParents.contains(parent)) {
                kept.add(line);
            }
        }
        return kept;
    }

    private Map<UUID, String> codeById() {
        Map<UUID, String> codes = new HashMap<>();
        snapshot.resources().values().forEach(r -> codes.put(r.id(), r.code()));
        return codes;
    }

    /** Depth-first over parent -> component codes; a back edge is a cycle. */
    private void checkCycles() {
        Map<String, List<String>> edges = new HashMap<>();
        Map<String, Integer> rowOf = new HashMap<>();
        for (FileLine line : file.lines) {
            edges.computeIfAbsent(line.parentCode(), k -> new ArrayList<>()).add(line.componentCode());
            rowOf.putIfAbsent(line.parentCode() + "\u001f" + line.componentCode(), line.row());
        }
        Map<UUID, String> codeById = codeById();
        for (ExistingLine line : keptLines()) {
            edges.computeIfAbsent(codeById.get(line.parentId()), k -> new ArrayList<>())
                    .add(codeById.get(line.componentId()));
        }
        Map<String, Integer> state = new HashMap<>();
        for (String start : edges.keySet()) {
            if (state.containsKey(start)) {
                continue;
            }
            List<String> path = new ArrayList<>();
            List<java.util.Iterator<String>> stack = new ArrayList<>();
            state.put(start, 1);
            path.add(start);
            stack.add(edges.getOrDefault(start, List.of()).iterator());
            while (!stack.isEmpty()) {
                java.util.Iterator<String> it = stack.get(stack.size() - 1);
                if (!it.hasNext()) {
                    state.put(path.remove(path.size() - 1), 2);
                    stack.remove(stack.size() - 1);
                    continue;
                }
                String next = it.next();
                Integer seen = state.get(next);
                if (seen == null) {
                    state.put(next, 1);
                    path.add(next);
                    stack.add(edges.getOrDefault(next, List.of()).iterator());
                } else if (seen == 1) {
                    String parent = path.get(path.size() - 1);
                    throw new ImportFailedException("ANALYSIS_CYCLE", PresupuestoFile.ANALYSES,
                            rowOf.get(parent + "\u001f" + next), "CodigoI",
                            "el análisis de '" + next + "' se incluye a sí mismo (pasando por '" + parent + "')");
                }
            }
        }
    }

    /** Derived costs of every composite after the import, by code. */
    private Map<String, BigDecimal> derive() {
        Map<String, DerivedCost.Component> components = new HashMap<>();
        for (ExistingResource r : snapshot.resources().values()) {
            components.put(r.code(), new DerivedCost.Component(r.id(), r.type(), composites.contains(r.code()),
                    r.percentage(), r.currentCost()));
        }
        for (FileResource r : file.resources.values()) {
            boolean composite = composites.contains(r.code());
            components.put(r.code(), new DerivedCost.Component(resourceIds.get(r.code()), r.type(), composite,
                    r.percentage(), composite ? null : r.cost()));
        }
        List<DerivedCost.Line> lines = new ArrayList<>();
        for (int i = 0; i < file.lines.size(); i++) {
            FileLine line = file.lines.get(i);
            lines.add(new DerivedCost.Line(lineIds.get(i), resourceIds.get(line.parentCode()),
                    resourceIds.get(line.componentCode()), line.lineNo(), line.operator(), line.quantity()));
        }
        for (ExistingLine line : keptLines()) {
            lines.add(new DerivedCost.Line(line.id(), line.parentId(), line.componentId(), line.lineNo(),
                    line.operator(), line.quantity()));
        }
        Map<UUID, DerivedCost.Result> results = DerivedCost.derive(components.values(), lines);
        Map<String, BigDecimal> derived = new HashMap<>();
        for (String code : composites) {
            DerivedCost.Result result = results.get(components.get(code).id());
            if (result != null) {
                derived.put(code, result.cost());
            }
        }
        for (FileResource r : file.resources.values()) {
            BigDecimal cost = derived.get(r.code());
            if (cost != null && DerivedCost.mismatch(cost, r.cost())) {
                file.warnings.add("WORK_ITEM_COST_MISMATCH", PresupuestoFile.CATALOG, r.row(), r.code(),
                        Map.of("imported", money(r.cost()), "derived", money(cost)));
            }
        }
        return derived;
    }

    private void checkLabor() {
        Set<String> seen = new HashSet<>();
        for (FileLabor row : file.labor) {
            FileResource r = file.resources.get(row.code());
            if (r.type() != ResourceType.LABOR || composites.contains(r.code()) || !seen.add(r.code())) {
                continue;
            }
            labor.add(row);
            if (row.realWage() != null && r.cost().subtract(row.realWage()).abs().compareTo(LABOR_TOLERANCE) > 0) {
                file.warnings.add("LABOR_COST_MISMATCH", PresupuestoFile.LABOR, row.row(), r.code(),
                        Map.of("cost", money(r.cost()), "realWage", money(row.realWage())));
            }
        }
    }

    private void summarize(Map<String, BigDecimal> derived) {
        summary.put("rows", file.rowCounts);
        Map<ResourceType, Map<String, Integer>> byType = new LinkedHashMap<>();
        for (ResourceType type : ResourceType.values()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("new", 0);
            counts.put("costChanged", 0);
            counts.put("unchanged", 0);
            counts.put("missingFromFile", 0);
            byType.put(type, counts);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        for (FileResource r : file.resources.values()) {
            ExistingResource existing = snapshot.resources().get(r.code());
            Map<String, Integer> counts = byType.get(r.type());
            if (existing == null) {
                counts.merge("new", 1, Integer::sum);
                continue;
            }
            BigDecimal to = composites.contains(r.code()) ? derived.get(r.code()) : r.cost();
            BigDecimal from = existing.currentCost();
            boolean changed = from == null ? to != null : to == null || from.compareTo(to) != 0;
            if (changed) {
                counts.merge("costChanged", 1, Integer::sum);
                if (changes.size() < ImportWarnings.SAMPLE_MAX) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("resourceCode", r.code());
                    change.put("type", r.type().name());
                    change.put("name", r.name());
                    change.put("from", money(from));
                    change.put("to", money(to));
                    changes.add(change);
                }
            } else {
                counts.merge("unchanged", 1, Integer::sum);
            }
        }
        List<Map<String, Object>> missingSample = new ArrayList<>();
        for (ExistingResource r : missing) {
            byType.get(r.type()).merge("missingFromFile", 1, Integer::sum);
            if (missingSample.size() < ImportWarnings.SAMPLE_MAX) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("resourceCode", r.code());
                entry.put("type", r.type().name());
                entry.put("name", r.name());
                missingSample.add(entry);
            }
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        byType.forEach((type, counts) -> resources.put(type.name(), counts));
        summary.put("resources", resources);
        summary.put("units", newAndUnchanged(units.stream().map(FileUnit::code).toList(), snapshot.units().keySet()));
        summary.put("families", newAndUnchanged(file.families.stream().map(FileFamily::code).toList(),
                snapshot.families().keySet()));
        summary.put("suppliers", newAndUnchanged(file.suppliers.stream().map(FileSupplier::code).toList(),
                snapshot.suppliersByCode().keySet()));
        Map<String, Object> fasar = new LinkedHashMap<>();
        fasar.put("provided", false);
        fasar.put("year", null);
        fasar.put("uma", null);
        fasar.put("minimumWage", file.generalFields == null ? null : money(file.generalFields.minimumWage()));
        fasar.put("laborCategories", labor.size());
        fasar.put("fsrMismatches", 0);
        fasar.put("realWageMismatches", 0);
        summary.put("fasar", fasar);
        summary.put("warnings", file.warnings.toSummary());
        summary.put("changes", Map.of("sample", changes));
        summary.put("missingFromFile", Map.of("sample", missingSample));
    }

    private static Map<String, Integer> newAndUnchanged(List<String> codes, Set<String> existing) {
        int created = 0;
        int unchanged = 0;
        for (String code : codes) {
            if (existing.contains(code)) {
                unchanged++;
            } else {
                created++;
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("new", created);
        out.put("unchanged", unchanged);
        return out;
    }

    static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}

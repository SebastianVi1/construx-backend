package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.importing.CatalogSnapshot;
import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingLine;
import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingResource;
import org.andre.construx.modules.pricing.importing.CatalogSnapshot.ExistingSupplier;
import org.andre.construx.modules.pricing.importing.ImportPlan;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileEquipment;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileEquipmentLine;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileFamily;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileLabor;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileLine;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileResource;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileStandby;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileSupplier;
import org.andre.construx.modules.pricing.importing.PresupuestoFile.FileUnit;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.FasarCalculator;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Catalog as the import sees it: a plain-value snapshot to plan against, and the write of a plan
 * (docs/pricing.md §4.6) with JDBC batches, since a real export is 35 000 Resources and 82 000 analysis lines.
 * The write runs inside the caller's transaction.
 */
@Component
class CatalogImportStore {
    private static final String SOURCE = CatalogSource.NEODATA_CONSTRUBASE.name();
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final JdbcTemplate jdbc;

    CatalogImportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CatalogSnapshot snapshot(UUID organizationId) {
        Map<String, UUID> units = new HashMap<>();
        jdbc.query("select code, id from unit where organization_id = ?",
                rs -> { units.put(rs.getString(1), rs.getObject(2, UUID.class)); }, organizationId);
        Map<String, UUID> families = new HashMap<>();
        jdbc.query("select code, id from family where organization_id = ?",
                rs -> { families.put(rs.getString(1), rs.getObject(2, UUID.class)); }, organizationId);
        Map<String, ExistingSupplier> suppliers = new HashMap<>();
        jdbc.query("select id, code, normalized_key from supplier where organization_id = ? and code is not null",
                rs -> {
                    suppliers.put(rs.getString(2), new ExistingSupplier(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3)));
                }, organizationId);
        Map<String, ExistingResource> resources = new HashMap<>();
        jdbc.query("select id, code, type, name, is_composite, is_percentage, current_cost, cost_updated_at, source "
                + "from resource where organization_id = ?", rs -> {
                    Timestamp updated = rs.getTimestamp(8);
                    resources.put(rs.getString(2), new ExistingResource(rs.getObject(1, UUID.class), rs.getString(2),
                            ResourceType.valueOf(rs.getString(3)), rs.getString(4), rs.getBoolean(5),
                            rs.getBoolean(6), rs.getBigDecimal(7), updated == null ? null : updated.toInstant(),
                            CatalogSource.valueOf(rs.getString(9))));
                }, organizationId);
        List<ExistingLine> lines = new ArrayList<>();
        jdbc.query("select id, parent_resource_id, component_resource_id, line_no, operator, quantity "
                + "from unit_price_analysis_line where organization_id = ?", rs -> {
                    lines.add(new ExistingLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5).charAt(0),
                            rs.getBigDecimal(6)));
                }, organizationId);
        return new CatalogSnapshot(units, families, suppliers, resources, lines);
    }

    /** Applies the plan in the order of §4.6, up to (not including) the derived-cost recomputation. */
    @Transactional(propagation = Propagation.MANDATORY)
    void write(UUID organizationId, UUID importId, ImportPlan plan, Instant now) {
        Timestamp at = Timestamp.from(now);
        ensureCatalog(organizationId, at);
        writeUnits(organizationId, plan);
        writeFamilies(organizationId, plan);
        writeSuppliers(organizationId, plan);
        writeResources(organizationId, importId, plan, now);
        writeLabor(organizationId, plan);
        writeEquipment(organizationId, plan);
        writeAnalyses(organizationId, plan);
        List<Object[]> missing = plan.missing.stream().map(r -> new Object[] {at, r.id()}).toList();
        jdbc.batchUpdate("update resource set missing_from_import_at = ? where id = ? and missing_from_import_at is null",
                missing);
    }

    void finish(UUID organizationId, UUID importId) {
        jdbc.update("update catalog set last_import_id = ? where organization_id = ?", importId, organizationId);
    }

    private void ensureCatalog(UUID organizationId, Timestamp at) {
        Integer count = jdbc.queryForObject("select count(*) from catalog where organization_id = ?", Integer.class,
                organizationId);
        if (count == null || count == 0) {
            jdbc.update("insert into catalog (id, organization_id, currency, created_at) values (?, ?, 'MXN', ?)",
                    UUID.randomUUID(), organizationId, at);
        }
    }

    private void writeUnits(UUID organizationId, ImportPlan plan) {
        Set<String> listed = new HashSet<>();
        plan.file.unitsListed().forEach(u -> listed.add(u.code()));
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (FileUnit unit : plan.units) {
            UUID id = plan.unitIds.get(unit.code());
            if (!plan.snapshot.units().containsKey(unit.code())) {
                inserts.add(new Object[] {id, organizationId, unit.code(), unit.description(), SOURCE});
            } else if (listed.contains(unit.code())) {
                updates.add(new Object[] {unit.description(), id});
            }
        }
        jdbc.batchUpdate("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, ?, ?)",
                inserts);
        jdbc.batchUpdate("update unit set description = ? where id = ?", updates);
    }

    private void writeFamilies(UUID organizationId, ImportPlan plan) {
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (FileFamily family : plan.families()) {
            UUID id = plan.familyIds.get(family.code());
            if (plan.snapshot.families().containsKey(family.code())) {
                updates.add(new Object[] {family.name(), id});
            } else {
                inserts.add(new Object[] {id, organizationId, family.code(), family.name(), SOURCE});
            }
        }
        jdbc.batchUpdate("insert into family (id, organization_id, code, name, source) values (?, ?, ?, ?, ?)",
                inserts);
        jdbc.batchUpdate("update family set name = ? where id = ?", updates);
    }

    /**
     * By code. A name that collides with another supplier's name + branch becomes a second row with its code as
     * branch (§1.4): the import never merges by name and never fails on one.
     */
    private void writeSuppliers(UUID organizationId, ImportPlan plan) {
        Map<String, UUID> keys = new HashMap<>();
        jdbc.query("select id, normalized_key from supplier where organization_id = ?",
                rs -> { keys.put(rs.getString(2), rs.getObject(1, UUID.class)); }, organizationId);
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (FileSupplier s : plan.suppliers()) {
            UUID id = plan.supplierIds.get(s.code());
            String branch = null;
            String key = Supplier.keyOf(s.name(), null);
            UUID holder = keys.get(key);
            if (holder != null && !holder.equals(id)) {
                branch = s.code().length() > Supplier.BRANCH_MAX ? s.code().substring(0, Supplier.BRANCH_MAX)
                        : s.code();
                key = Supplier.keyOf(s.name(), branch);
            }
            keys.values().remove(id);
            keys.put(key, id);
            String email = s.email() != null && EMAIL.matcher(s.email()).matches() ? s.email() : null;
            String website = s.website() != null && s.website().matches("(?i)https?://\\S+") ? s.website() : null;
            Object[] fields = {s.name(), branch, website, key, s.rfc(), s.city(), s.state(), s.address(),
                    s.neighborhood(), s.postalCode(), s.phone(), s.mobile(), s.fax(), email, s.contact(), s.notes(),
                    s.isDefault()};
            if (plan.snapshot.suppliersByCode().containsKey(s.code())) {
                Object[] row = new Object[fields.length + 1];
                System.arraycopy(fields, 0, row, 0, fields.length);
                row[fields.length] = id;
                updates.add(row);
            } else {
                Object[] row = new Object[fields.length + 3];
                row[0] = id;
                row[1] = organizationId;
                row[2] = s.code();
                System.arraycopy(fields, 0, row, 3, fields.length);
                inserts.add(row);
            }
        }
        jdbc.batchUpdate("insert into supplier (id, organization_id, code, name, branch, website, normalized_key, rfc, "
                + "city, state, address, neighborhood, postal_code, phone, mobile, fax, email, contact, notes, "
                + "is_default, source) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '"
                + SOURCE + "')", inserts);
        jdbc.batchUpdate("update supplier set name = ?, branch = ?, website = ?, normalized_key = ?, rfc = ?, city = ?, "
                + "state = ?, address = ?, neighborhood = ?, postal_code = ?, phone = ?, mobile = ?, fax = ?, "
                + "email = ?, contact = ?, notes = ?, is_default = ?, source = '" + SOURCE + "' where id = ?", updates);
    }

    /**
     * New rows or every descriptive field overwritten; a simple Resource whose file cost differs from its current
     * cost gets one Cost history entry of this import.
     */
    private void writeResources(UUID organizationId, UUID importId, ImportPlan plan, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<Object[]> costs = new ArrayList<>();
        List<Object[]> history = new ArrayList<>();
        for (FileResource r : plan.resources()) {
            UUID id = plan.resourceIds.get(r.code());
            boolean composite = plan.composites.contains(r.code());
            ExistingResource existing = plan.snapshot.resources().get(r.code());
            BigDecimal cost = composite ? null : plan.cost(r.code());
            Object[] fields = {r.type().name(), r.name(), r.description(), plan.unitIds.get(r.unitCode()),
                    plan.resourceFamily.get(r.code()), plan.resourceSupplier.get(r.code()), r.percentage(),
                    r.percentageBase(), composite, r.hourlyCost(), r.discontinued(), r.reference(), r.sapCode(),
                    r.technicalSheet(), r.grouper(), r.procedure(), r.cost(),
                    r.costOn() == null ? null : java.sql.Date.valueOf(r.costOn()), at};
            if (existing == null) {
                Object[] row = new Object[fields.length + 6];
                row[0] = id;
                row[1] = organizationId;
                row[2] = r.code();
                System.arraycopy(fields, 0, row, 3, fields.length);
                row[fields.length + 3] = cost;
                row[fields.length + 4] = composite ? null : at;
                row[fields.length + 5] = at;
                inserts.add(row);
                if (!composite) {
                    history.add(new Object[] {UUID.randomUUID(), organizationId, id, cost, at, importId});
                }
                continue;
            }
            Object[] row = new Object[fields.length + 1];
            System.arraycopy(fields, 0, row, 0, fields.length);
            row[fields.length] = id;
            updates.add(row);
            BigDecimal current = existing.currentCost();
            if (!composite && (current == null || current.compareTo(cost) != 0)) {
                Instant effective = existing.costUpdatedAt() != null && !now.isAfter(existing.costUpdatedAt())
                        ? existing.costUpdatedAt().plusNanos(1000) : now;
                Timestamp when = Timestamp.from(effective);
                history.add(new Object[] {UUID.randomUUID(), organizationId, id, cost, when, importId});
                costs.add(new Object[] {cost, when, id});
            }
        }
        String columns = "type, name, description, unit_id, family_id, supplier_id, is_percentage, percentage_base, "
                + "is_composite, is_hourly_cost, discontinued, reference, sap_code, technical_sheet, grouper, "
                + "procedure, imported_cost, imported_cost_on, updated_at";
        jdbc.batchUpdate("insert into resource (id, organization_id, code, " + columns + ", current_cost, "
                + "cost_updated_at, created_at, source) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?, ?, ?, ?, '" + SOURCE + "')", inserts);
        jdbc.batchUpdate("update resource set type = ?, name = ?, description = ?, unit_id = ?, family_id = ?, "
                + "supplier_id = ?, is_percentage = ?, percentage_base = ?, is_composite = ?, is_hourly_cost = ?, "
                + "discontinued = ?, reference = ?, sap_code = ?, technical_sheet = ?, grouper = ?, procedure = ?, "
                + "imported_cost = ?, imported_cost_on = ?, updated_at = ?, source = '" + SOURCE + "', "
                + "missing_from_import_at = null where id = ?", updates);
        jdbc.batchUpdate("update resource set current_cost = ?, cost_updated_at = ? where id = ?", costs);
        jdbc.batchUpdate("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, "
                + "source, import_id) values (?, ?, ?, ?, ?, '" + SOURCE + "', ?)", history);
    }

    /**
     * Without a complete FASAR set Labor categories keep the file's FSR ({@code IMPORTED}) and their cost is the
     * file's {@code Costo0}; with one they are {@code COMPUTED} (§5.5) and keep the verified file values.
     */
    private void writeLabor(UUID organizationId, ImportPlan plan) {
        List<Object[]> deletes = new ArrayList<>();
        List<Object[]> inserts = new ArrayList<>();
        for (FileLabor l : plan.labor) {
            UUID id = plan.resourceIds.get(l.code());
            deletes.add(new Object[] {id});
            FasarCalculator.Wage wage = plan.laborWages.get(l.code());
            ImportPlan.Verified verified = plan.laborImported.get(l.code());
            inserts.add(wage == null
                    ? new Object[] {id, organizationId, l.nominalWage(), l.fsr(), "IMPORTED", l.fsbc(), l.sbc(),
                            l.fsr(), l.realWage(), l.fsbc(), l.sbc(), l.perDiem(), l.mealsLodging(),
                            l.funeralExpenses(), l.otherBenefits(), l.fasarType(), false}
                    : new Object[] {id, organizationId, l.nominalWage(), wage.fsr().setScale(8, RoundingMode.HALF_UP),
                            "COMPUTED", wage.fsbc(), wage.sbc(), verified.fsr(), verified.realWage(), l.fsbc(),
                            l.sbc(), l.perDiem(), l.mealsLodging(), l.funeralExpenses(), l.otherBenefits(),
                            l.fasarType(), verified.fsrMismatch() || verified.realWageMismatch()});
        }
        jdbc.batchUpdate("delete from labor_category_detail where resource_id = ?", deletes);
        jdbc.batchUpdate("insert into labor_category_detail (resource_id, organization_id, nominal_wage, fsr, "
                + "fsr_source, fsbc, sbc, imported_fsr, imported_real_wage, imported_fsbc, imported_sbc, per_diem, "
                + "meals_lodging, funeral_expenses, other_benefits, fasar_type, fsr_mismatch) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", inserts);
    }

    private void writeEquipment(UUID organizationId, ImportPlan plan) {
        Set<UUID> detailed = new HashSet<>();
        List<Object[]> details = new ArrayList<>();
        String columns = null;
        for (FileEquipment e : plan.equipment()) {
            UUID id = plan.resourceIds.get(e.code());
            detailed.add(id);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("resource_id", id);
            values.put("organization_id", organizationId);
            values.putAll(e.texts());
            e.numbers().forEach((column, value) -> values.put(column,
                    value != null && (column.equals("calculation_type") || column.equals("fuel_type"))
                            ? (Object) value.shortValue() : value));
            columns = String.join(", ", values.keySet());
            details.add(values.values().toArray());
        }
        jdbc.batchUpdate("delete from equipment_rate_detail where resource_id = ?",
                detailed.stream().map(id -> new Object[] {id}).toList());
        if (columns != null) {
            jdbc.batchUpdate("insert into equipment_rate_detail (" + columns + ") values ("
                    + "?, ".repeat(details.get(0).length - 1) + "?)", details);
        }
        replaceEquipmentLines(organizationId, plan, "equipment_operation_line", plan.operationLines());
        replaceEquipmentLines(organizationId, plan, "equipment_consumption_line", plan.consumptionLines());
        jdbc.update("delete from equipment_standby_percentage where organization_id = ?", organizationId);
        List<Object[]> standby = new ArrayList<>();
        for (FileStandby s : plan.standby()) {
            standby.add(new Object[] {UUID.randomUUID(), organizationId, s.charge(), s.standbyPct(), s.reservePct()});
        }
        jdbc.batchUpdate("insert into equipment_standby_percentage (id, organization_id, charge, standby_pct, "
                + "reserve_pct) values (?, ?, ?, ?, ?)", standby);
    }

    /** Lines of every equipment named in the sheet are replaced; a repeated component keeps its first line. */
    private void replaceEquipmentLines(UUID organizationId, ImportPlan plan, String table,
                                       List<FileEquipmentLine> lines) {
        Set<UUID> equipment = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Map<UUID, Integer> next = new HashMap<>();
        List<Object[]> inserts = new ArrayList<>();
        for (FileEquipmentLine line : lines) {
            UUID parent = plan.resourceIds.get(line.equipmentCode());
            UUID component = plan.resourceIds.get(line.componentCode());
            equipment.add(parent);
            if (!seen.add(parent + "/" + component)) {
                continue;
            }
            int lineNo = next.merge(parent, 1, Integer::sum);
            inserts.add(new Object[] {UUID.randomUUID(), organizationId, parent, component,
                    String.valueOf(line.operator()), line.quantity(), lineNo});
        }
        jdbc.batchUpdate("delete from " + table + " where equipment_resource_id = ?",
                equipment.stream().map(id -> new Object[] {id}).toList());
        jdbc.batchUpdate("insert into " + table + " (id, organization_id, equipment_resource_id, component_resource_id, "
                + "operator, quantity, line_no) values (?, ?, ?, ?, ?, ?, ?)", inserts);
    }

    /** Parents present in {@code Matrices} get their lines replaced; a repeated or missing line number moves last. */
    private void writeAnalyses(UUID organizationId, ImportPlan plan) {
        jdbc.batchUpdate("delete from unit_price_analysis_line where parent_resource_id = ?",
                plan.analysisParents.stream().map(code -> new Object[] {plan.resourceIds.get(code)}).toList());
        Map<String, Set<Integer>> used = new HashMap<>();
        Map<String, Integer> max = new HashMap<>();
        for (FileLine line : plan.lines()) {
            max.merge(line.parentCode(), line.lineNo(), Math::max);
        }
        List<Object[]> inserts = new ArrayList<>();
        List<FileLine> lines = plan.lines();
        for (int i = 0; i < lines.size(); i++) {
            FileLine line = lines.get(i);
            int lineNo = line.lineNo();
            Set<Integer> taken = used.computeIfAbsent(line.parentCode(), k -> new HashSet<>());
            if (lineNo <= 0 || taken.contains(lineNo)) {
                lineNo = max.merge(line.parentCode(), 1, Integer::sum);
            }
            taken.add(lineNo);
            inserts.add(new Object[] {plan.lineIds.get(i), organizationId, plan.resourceIds.get(line.parentCode()),
                    plan.resourceIds.get(line.componentCode()), lineNo, String.valueOf(line.operator()),
                    line.quantity(), line.expression()});
        }
        jdbc.batchUpdate("insert into unit_price_analysis_line (id, organization_id, parent_resource_id, "
                + "component_resource_id, line_no, operator, quantity, expression) values (?, ?, ?, ?, ?, ?, ?, ?)",
                inserts);
    }
}

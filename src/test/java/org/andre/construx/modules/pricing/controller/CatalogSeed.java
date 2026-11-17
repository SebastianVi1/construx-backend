package org.andre.construx.modules.pricing.controller;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes Catalog rows straight to the database for the read flow tests: the write endpoints and the
 * import belong to later slices. Every row belongs to one organization.
 */
final class CatalogSeed {
    private final JdbcTemplate jdbc;
    private final UUID organizationId;

    CatalogSeed(JdbcTemplate jdbc, UUID organizationId) {
        this.jdbc = jdbc;
        this.organizationId = organizationId;
    }

    void catalog() {
        jdbc.update("insert into catalog (id, organization_id, currency, created_at) values (?, ?, 'MXN', ?)",
                UUID.randomUUID(), organizationId, Timestamp.from(Instant.now()));
    }

    UUID unit(String code, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, ?, 'SEED')",
                id, organizationId, code, description);
        return id;
    }

    UUID family(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into family (id, organization_id, code, name, source) values (?, ?, ?, ?, 'SEED')",
                id, organizationId, code, name);
        return id;
    }

    UUID supplier(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, ?, ?)",
                id, organizationId, name, name.toLowerCase() + "\u001f");
        return id;
    }

    /** A simple Resource without cost; refine it with {@link #set}. */
    UUID resource(String code, String type, String name, UUID unitId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into resource (id, organization_id, code, type, name, unit_id, is_percentage, is_composite, "
                        + "is_hourly_cost, discontinued, source, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, false, false, false, false, 'MANUAL', ?, ?)",
                id, organizationId, code, type, name, unitId, now, now);
        return id;
    }

    /** Sets one column of a Resource, e.g. {@code set(id, "family_id", familyId)}. */
    void set(UUID resourceId, String column, Object value) {
        jdbc.update("update resource set " + column + " = ? where id = ?", value, resourceId);
    }

    /** A Cost history row; the Resource's current cost follows the newest one, as the write path will. */
    UUID cost(UUID resourceId, String amount, Instant effectiveFrom, String source, UUID supplierId, String sku,
              String note, UUID recordedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, source, "
                        + "recorded_by_membership_id, supplier_id, sku, note) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, organizationId, resourceId, new BigDecimal(amount), Timestamp.from(effectiveFrom), source,
                recordedBy, supplierId, sku, note);
        jdbc.update("update resource set current_cost = (select h.amount from resource_cost_history h "
                + "where h.resource_id = resource.id order by h.effective_from desc limit 1), "
                + "cost_updated_at = (select max(h.effective_from) from resource_cost_history h "
                + "where h.resource_id = resource.id) where id = ?", resourceId);
        return id;
    }

    /** A composite Resource without cost: a Gang ({@code LABOR}), a composite Material or a Work item. */
    UUID composite(String code, String type, String name, UUID unitId) {
        UUID id = resource(code, type, name, unitId);
        set(id, "is_composite", true);
        return id;
    }

    /** A percentage Resource over the labor subtotal ({@code %MO}). */
    UUID percentage(String code, String name, UUID unitId) {
        UUID id = resource(code, "MATERIAL", name, unitId);
        set(id, "is_percentage", true);
        set(id, "percentage_base", (short) 2);
        return id;
    }

    /** One Unit price analysis line, as the import writes it. */
    UUID line(UUID parentId, int lineNo, UUID componentId, String operator, String quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into unit_price_analysis_line (id, organization_id, parent_resource_id, "
                        + "component_resource_id, line_no, operator, quantity) values (?, ?, ?, ?, ?, ?, ?)",
                id, organizationId, parentId, componentId, lineNo, operator, new BigDecimal(quantity));
        return id;
    }

    /** An imported Labor category detail; benefits are zero. */
    void laborDetail(UUID resourceId, String nominalWage, String fsr, String importedRealWage) {
        jdbc.update("insert into labor_category_detail (resource_id, organization_id, nominal_wage, fsr, fsr_source, "
                        + "imported_fsr, imported_real_wage, per_diem, meals_lodging, funeral_expenses, other_benefits, "
                        + "fsr_mismatch) values (?, ?, ?, ?, 'IMPORTED', ?, ?, 0, 0, 0, 0, false)",
                resourceId, organizationId, new BigDecimal(nominalWage), new BigDecimal(fsr), new BigDecimal(fsr),
                new BigDecimal(importedRealWage));
    }

    /** An imported Equipment rate detail with a few machinery values. */
    void equipmentDetail(UUID resourceId) {
        jdbc.update("insert into equipment_rate_detail (resource_id, organization_id, power_unit, power, "
                        + "economic_life_hours, hours_per_year, operation_factor, salvage_pct, interest_rate, "
                        + "brand, model, commercial_value, fuel_qty_per_hour) "
                        + "values (?, ?, 'HP', 95.5, 12000, 2000, 0.8, 0.1, 0.12, 'CAT', '416F', 1500000, 0.15)",
                resourceId, organizationId);
    }

    /** One operation ({@code operation = true}) or consumption line of an Equipment rate. */
    void equipmentLine(boolean operation, UUID equipmentId, int lineNo, UUID componentId, String operator,
                       String quantity) {
        jdbc.update("insert into " + (operation ? "equipment_operation_line" : "equipment_consumption_line")
                        + " (id, organization_id, equipment_resource_id, component_resource_id, operator, quantity, "
                        + "line_no) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), organizationId, equipmentId, componentId, operator, new BigDecimal(quantity),
                lineNo);
    }

    void standby(String charge, String standbyPct, String reservePct) {
        jdbc.update("insert into equipment_standby_percentage (id, organization_id, charge, standby_pct, reserve_pct) "
                + "values (?, ?, ?, ?, ?)", UUID.randomUUID(), organizationId, charge, new BigDecimal(standbyPct),
                new BigDecimal(reservePct));
    }
}

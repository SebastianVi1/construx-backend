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
}

package org.andre.construx.modules.pricing.controller;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Catalog rows written straight to the database: this slice has no write endpoints for them. */
final class CatalogRows {
    private final JdbcTemplate jdbc;
    private final UUID org;

    CatalogRows(JdbcTemplate jdbc, UUID org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    void catalog() {
        jdbc.update("insert into catalog (id, organization_id, currency, created_at) values (?, ?, 'MXN', ?)",
                UUID.randomUUID(), org, Timestamp.from(Instant.now()));
    }

    UUID unit(String code, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into unit (id, organization_id, code, description, source) values (?, ?, ?, ?, 'SEED')",
                id, org, code, description);
        return id;
    }

    UUID family(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into family (id, organization_id, code, name, source) values (?, ?, ?, ?, 'MANUAL')",
                id, org, code, name);
        return id;
    }

    UUID supplier(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into supplier (id, organization_id, name, normalized_key) values (?, ?, ?, ?)",
                id, org, name, id.toString());
        return id;
    }

    void archive(String table, UUID id) {
        jdbc.update("update " + table + " set archived_at = ? where id = ?", Timestamp.from(Instant.now()), id);
    }

    Res resource(String code, String type, String name, UUID unit) {
        return new Res(code, type, name, unit);
    }

    /** A Resource row; set what the test needs, then {@link #insert()}. */
    final class Res {
        final UUID id = UUID.randomUUID();
        private final String code;
        private final String type;
        private final String name;
        private final UUID unit;
        private UUID family;
        private UUID supplier;
        private String description;
        private boolean composite;
        private boolean discontinued;
        private Instant missingFromImportAt;
        private Instant archivedAt;

        private Res(String code, String type, String name, UUID unit) {
            this.code = code;
            this.type = type;
            this.name = name;
            this.unit = unit;
        }

        Res family(UUID value) { family = value; return this; }
        Res supplier(UUID value) { supplier = value; return this; }
        Res description(String value) { description = value; return this; }
        Res composite() { composite = true; return this; }
        Res discontinued() { discontinued = true; return this; }
        Res missingFromImport() { missingFromImportAt = Instant.now(); return this; }
        Res archived() { archivedAt = Instant.now(); return this; }

        UUID insert() {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into resource (id, organization_id, code, type, name, description, unit_id, family_id, "
                            + "supplier_id, is_percentage, is_composite, is_hourly_cost, discontinued, source, "
                            + "missing_from_import_at, archived_at, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, false, ?, 'NEODATA_CONSTRUBASE', ?, ?, ?, ?)",
                    id, org, code, type, name, description, unit, family, supplier, composite, discontinued,
                    ts(missingFromImportAt), ts(archivedAt), now, now);
            return id;
        }
    }

    /** Appends a history entry and makes it the current cost, as the service writes them. */
    UUID cost(UUID resource, String amount, Instant effectiveFrom, UUID supplier, UUID recordedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into resource_cost_history (id, organization_id, resource_id, amount, effective_from, source, "
                        + "recorded_by_membership_id, supplier_id, sku, note) values (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?)",
                id, org, resource, new BigDecimal(amount), ts(effectiveFrom), recordedBy, supplier,
                supplier == null ? null : "SKU-" + amount, recordedBy == null ? null : "nota " + amount);
        jdbc.update("update resource set current_cost = ?, cost_updated_at = ? where id = ?",
                new BigDecimal(amount), ts(effectiveFrom), resource);
        return id;
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

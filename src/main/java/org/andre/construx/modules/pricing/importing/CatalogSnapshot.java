package org.andre.construx.modules.pricing.importing;

import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.ResourceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the organization's Catalog holds before an import, as plain values: the dry run classifies the file
 * against it and the write merges into it by code.
 */
public record CatalogSnapshot(Map<String, UUID> units, Map<String, UUID> families,
                              Map<String, ExistingSupplier> suppliersByCode, Map<String, ExistingResource> resources,
                              List<ExistingLine> lines) {

    public record ExistingSupplier(UUID id, String code, String normalizedKey) {}

    public record ExistingResource(UUID id, String code, ResourceType type, String name, boolean composite,
                                   boolean percentage, BigDecimal currentCost, Instant costUpdatedAt,
                                   CatalogSource source) {}

    public record ExistingLine(UUID id, UUID parentId, UUID componentId, int lineNo, char operator,
                               BigDecimal quantity) {}

    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }
}

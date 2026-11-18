package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.pricing.model.PricingText;

/** The text search of the Catalog lists (Resources, Suppliers): an accent- and case-folded contains match. */
final class CatalogSearch {
    private CatalogSearch() {
    }

    /**
     * The LIKE pattern of a contains search over folded text, with backslash, {@code %} and {@code _} escaped by a
     * backslash (the queries say {@code escape}); null when the query is blank, which means no filter.
     */
    static String containsPattern(String query) {
        String folded = PricingText.fold(query);
        if (folded.isEmpty()) {
            return null;
        }
        return "%" + folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

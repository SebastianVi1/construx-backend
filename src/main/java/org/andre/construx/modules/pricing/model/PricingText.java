package org.andre.construx.modules.pricing.model;

import java.util.Locale;

/** Text cleanup shared by the catalog entities and their uniqueness keys. */
public final class PricingText {
    /** Separates key parts; a control character nobody types into a name. */
    static final String SEPARATOR = "\u001f";

    private PricingText() {
    }

    /** Trims and collapses inner whitespace; blank becomes null. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Cleaned and lower-cased, empty when absent, for comparing names. */
    public static String fold(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.ROOT);
    }

    /** A Catalog code: trimmed and upper-cased, never otherwise normalized ({@code 1:4}, {@code %MO1}). */
    static String code(String value, int max, String label) {
        return code(value, max, label, "code");
    }

    static String code(String value, int max, String label, String field) {
        String cleaned = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.length() > max) {
            throw new PricingRuleException(field, cleaned.isEmpty() ? "REQUIRED" : "INVALID",
                    label + " must have 1 to " + max + " characters");
        }
        return cleaned;
    }

    /** An optional Catalog code; blank becomes null. */
    static String optionalCode(String value, int max, String label, String field) {
        return value == null || value.isBlank() ? null : code(value, max, label, field);
    }

    static String required(String value, int max, String label, String field) {
        String cleaned = clean(value);
        if (cleaned == null || cleaned.length() > max) {
            throw new PricingRuleException(field, cleaned == null ? "REQUIRED" : "INVALID",
                    label + " must have 1 to " + max + " characters");
        }
        return cleaned;
    }

    static String optional(String value, int max, String label, String field) {
        String cleaned = clean(value);
        if (cleaned != null && cleaned.length() > max) {
            throw new PricingRuleException(field, "INVALID", label + " must have at most " + max + " characters");
        }
        return cleaned;
    }
}

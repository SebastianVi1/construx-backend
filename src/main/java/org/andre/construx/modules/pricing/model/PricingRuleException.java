package org.andre.construx.modules.pricing.model;

/**
 * A pricing value that breaks a model rule, naming the field and a stable code (REQUIRED, INVALID,
 * TOO_MANY_DECIMALS, FUTURE_DATE) so callers such as the CSV import can report it per field. Still an
 * {@link IllegalArgumentException}, so single requests keep answering 400.
 */
public class PricingRuleException extends IllegalArgumentException {
    private final String field;
    private final String code;

    public PricingRuleException(String field, String code, String message) {
        super(message);
        this.field = field;
        this.code = code;
    }

    public String getField() {
        return field;
    }

    public String getCode() {
        return code;
    }
}

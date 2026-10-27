package org.andre.construx.modules.pricing.model;

/** A pricing rule conflict with a stable error code, answered with 409. */
public class PricingConflictException extends RuntimeException {
    private final String code;

    public PricingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

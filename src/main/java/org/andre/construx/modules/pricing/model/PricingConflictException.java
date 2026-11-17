package org.andre.construx.modules.pricing.model;

import java.util.Map;

/** A pricing rule conflict with a stable error code, answered with 409; {@code properties} join the problem body. */
public class PricingConflictException extends RuntimeException {
    private final String code;
    private final Map<String, Object> properties;

    public PricingConflictException(String code, String message) {
        this(code, message, Map.of());
    }

    public PricingConflictException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public String getCode() {
        return code;
    }
}

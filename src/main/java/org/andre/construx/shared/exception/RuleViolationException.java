package org.andre.construx.shared.exception;

import java.util.Map;

/**
 * A 400 with its own error {@code code}, so clients can tell which business rule was broken. Optional
 * {@code properties} are added to the problem body as extra fields.
 */
public class RuleViolationException extends IllegalArgumentException {
    private final String code;
    private final Map<String, Object> properties;

    public RuleViolationException(String code, String message) {
        this(code, message, Map.of());
    }

    public RuleViolationException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}

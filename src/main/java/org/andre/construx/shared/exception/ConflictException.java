package org.andre.construx.shared.exception;

import java.util.Map;

/**
 * A 409 with its own error {@code code}: the request is well formed but the current state of what it names refuses
 * it (for example an archived Catalog resource). Modules pick the code; this package never names one. Optional
 * {@code properties} are added to the problem body as extra fields.
 */
public class ConflictException extends RuntimeException {
    private final String code;
    private final Map<String, Object> properties;

    public ConflictException(String code, String message) {
        this(code, message, Map.of());
    }

    public ConflictException(String code, String message, Map<String, Object> properties) {
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

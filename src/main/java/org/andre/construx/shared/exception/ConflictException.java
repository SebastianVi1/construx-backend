package org.andre.construx.shared.exception;

/**
 * A 409 with its own error {@code code}: the request is well formed but the current state of what it names refuses
 * it (for example an archived Catalog resource). Modules pick the code; this package never names one.
 */
public class ConflictException extends RuntimeException {
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package org.andre.construx.shared.exception;

/** A 404; {@code code} names what is missing when clients must tell cases apart (default {@code NOT_FOUND}). */
public class ResourceNotFoundException extends RuntimeException {
    private final String code;

    public ResourceNotFoundException(String message) {
        this("NOT_FOUND", message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

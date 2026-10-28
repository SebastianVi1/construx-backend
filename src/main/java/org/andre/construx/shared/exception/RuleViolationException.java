package org.andre.construx.shared.exception;

/** A 400 with its own error {@code code}, so clients can tell which business rule was broken. */
public class RuleViolationException extends IllegalArgumentException {
    private final String code;

    public RuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package org.andre.construx.shared.validation;

public final class DomainValidation {
    private DomainValidation() {
    }

    public static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}

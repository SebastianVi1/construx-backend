package org.andre.construx.shared.exception;

/**
 * A 403 with its own error {@code code}: the caller is allowed the operation in general, but a business rule refuses
 * it here (for example a role above the caller's own). Plain permission failures stay
 * {@link org.springframework.security.access.AccessDeniedException} with code {@code FORBIDDEN}. Modules pick the
 * code; this package never names one.
 */
public class ForbiddenRuleException extends RuntimeException {
    private final String code;

    public ForbiddenRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package org.andre.construx.modules.pricing.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;

/**
 * Pricing conflicts carry their own code and a readable detail (for example a duplicate material),
 * in the same problem shape as the global handler. Ordered first so the global catch-all does not win.
 */
@RestControllerAdvice(basePackageClasses = PricingExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PricingExceptionHandler {

    @ExceptionHandler(PricingConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(PricingConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicting state");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PricingValidationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidFile(PricingValidationException ex, HttpServletRequest request) {
        return validationFailed(ex.getMessage(), request);
    }

    /** The import endpoint needs a multipart request with a {@code file} part. */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ProblemDetail> handleMissingFile(Exception ex, HttpServletRequest request) {
        return validationFailed("Send the CSV as a multipart/form-data part named file.", request);
    }

    private static ResponseEntity<ProblemDetail> validationFailed(String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation failed");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}

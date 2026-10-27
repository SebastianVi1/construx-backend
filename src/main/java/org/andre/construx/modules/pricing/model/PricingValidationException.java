package org.andre.construx.modules.pricing.model;

/** A whole pricing request that cannot be processed, such as an unreadable CSV file; answered with 400. */
public class PricingValidationException extends RuntimeException {
    public PricingValidationException(String message) {
        super(message);
    }
}

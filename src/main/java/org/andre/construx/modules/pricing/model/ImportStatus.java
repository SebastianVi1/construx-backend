package org.andre.construx.modules.pricing.model;

/** The state of a Catalog import (docs/pricing.md §1.11). The first four are active; at most one per organization. */
public enum ImportStatus {
    PENDING, VALIDATING, VALIDATED, IMPORTING, DONE, FAILED, CANCELLED;

    public boolean isActive() {
        return this == PENDING || this == VALIDATING || this == VALIDATED || this == IMPORTING;
    }
}

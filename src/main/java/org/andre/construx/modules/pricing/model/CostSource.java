package org.andre.construx.modules.pricing.model;

/** Where a Cost history entry came from; {@code FASAR} is a wage recalculation. */
public enum CostSource {
    MANUAL, CSV, NEODATA_CONSTRUBASE, FASAR
}

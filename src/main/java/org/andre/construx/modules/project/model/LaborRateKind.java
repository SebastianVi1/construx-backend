package org.andre.construx.modules.project.model;

/** How a person's daily labor cost on a Site is set (docs/pricing.md §6.1). */
public enum LaborRateKind {
    /** The current real wage of a catalog Labor category; follows the catalog. */
    CATALOG,
    /** A negotiated daily amount; never follows the catalog. */
    FIXED,
    /** Explicitly no rate. */
    NONE
}

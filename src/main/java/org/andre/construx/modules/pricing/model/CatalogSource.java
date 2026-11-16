package org.andre.construx.modules.pricing.model;

/** Where a Catalog row came from. {@code SEED} only for Units and Families converted from the old enums. */
public enum CatalogSource {
    SEED, MANUAL, CSV, NEODATA_CONSTRUBASE
}

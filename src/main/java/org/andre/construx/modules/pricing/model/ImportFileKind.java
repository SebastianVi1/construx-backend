package org.andre.construx.modules.pricing.model;

/** Which workbook an import upload slot holds (docs/pricing.md §4.1). */
public enum ImportFileKind {
    /** The PU Win+ Presupuesto export, {@code .xlsx} only. */
    PRESUPUESTO,
    /** The optional FASAR workbook, {@code .xlsx} or {@code .xls}. */
    FASAR
}

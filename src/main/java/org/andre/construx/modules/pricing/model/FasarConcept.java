package org.andre.construx.modules.pricing.model;

/**
 * The twelve contribution concepts of a FASAR parameter set (docs/pricing.md §1.10): Neodata's
 * {@code a)TablaFactores} rows I to V, INFONAVIT, payroll tax and other charges.
 */
public enum FasarConcept {
    RISK, FIXED_FEE, EXCESS_3_UMA, CASH_BENEFITS, PENSIONERS_MEDICAL, DISABILITY_LIFE, RETIREMENT,
    UNEMPLOYMENT_OLD_AGE, DAYCARE, INFONAVIT, PAYROLL_TAX, OTHER_CHARGES
}

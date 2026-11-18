package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The wage side of a simple Labor category (docs/pricing.md §1.8). Its real wage, the Resource's cost, is
 * {@code round(nominalWage * fsr, 2)} plus the four daily benefits.
 */
@Entity
@Table(name = "labor_category_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LaborCategoryDetail {
    /** numeric(12,2) holds at most ten integer digits. */
    private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("10000000000");
    private static final BigDecimal FSR_MAX = new BigDecimal("3");
    private static final int FSR_DECIMALS = 8;

    @Id
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal nominalWage;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal fsr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FsrSource fsrSource;

    @Column(precision = 12, scale = 8)
    private BigDecimal fsbc;

    @Column(precision = 12, scale = 2)
    private BigDecimal sbc;

    @Column(precision = 12, scale = 8)
    private BigDecimal importedFsr;

    @Column(precision = 12, scale = 2)
    private BigDecimal importedRealWage;

    @Column(precision = 12, scale = 8)
    private BigDecimal importedFsbc;

    @Column(precision = 12, scale = 2)
    private BigDecimal importedSbc;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal perDiem;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal mealsLodging;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal funeralExpenses;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal otherBenefits;

    private Short fasarType;

    @Column(nullable = false)
    private boolean fsrMismatch;

    /** A Labor category typed by hand: nominal wage and a manual FSR from 1 to 3; missing benefits are zero. */
    public static LaborCategoryDetail manual(Resource resource, BigDecimal nominalWage, BigDecimal fsr,
                                             BigDecimal perDiem, BigDecimal mealsLodging,
                                             BigDecimal funeralExpenses, BigDecimal otherBenefits) {
        if (resource.getType() != ResourceType.LABOR || resource.isComposite()) {
            throw new PricingValidationException("Only labor categories have a labor detail");
        }
        LaborCategoryDetail detail = new LaborCategoryDetail();
        detail.resourceId = resource.getId();
        detail.organizationId = resource.getOrganizationId();
        detail.nominalWage = amount(nominalWage, "laborDetail.nominalWage");
        detail.fsr = manualFsr(fsr);
        detail.fsrSource = FsrSource.MANUAL;
        detail.perDiem = benefit(perDiem, "laborDetail.perDiem");
        detail.mealsLodging = benefit(mealsLodging, "laborDetail.mealsLodging");
        detail.funeralExpenses = benefit(funeralExpenses, "laborDetail.funeralExpenses");
        detail.otherBenefits = benefit(otherBenefits, "laborDetail.otherBenefits");
        return detail;
    }

    /** Tolerances of the verification against the file (docs/pricing.md §5.4). */
    private static final BigDecimal FSR_TOLERANCE = new BigDecimal("0.0005");
    private static final BigDecimal REAL_WAGE_TOLERANCE = new BigDecimal("0.05");

    /**
     * A Labor category typed by hand while the organization has complete FASAR parameters: the FSR is computed
     * from them and any typed FSR is ignored.
     */
    public static LaborCategoryDetail computed(Resource resource, BigDecimal nominalWage, BigDecimal perDiem,
                                               BigDecimal mealsLodging, BigDecimal funeralExpenses,
                                               BigDecimal otherBenefits, FasarCalculator fasar) {
        LaborCategoryDetail detail = manual(resource, nominalWage, BigDecimal.ONE, perDiem, mealsLodging,
                funeralExpenses, otherBenefits);
        detail.compute(fasar);
        return detail;
    }

    /**
     * Takes the FSR, FSBC and SBC computed from complete FASAR parameters for this nominal wage (source
     * {@code COMPUTED}) and verifies them against the imported values.
     */
    public void compute(FasarCalculator fasar) {
        FasarCalculator.Wage wage = fasar.wage(nominalWage);
        this.fsr = wage.fsr().setScale(FSR_DECIMALS, RoundingMode.HALF_UP);
        this.fsbc = wage.fsbc().setScale(FSR_DECIMALS, RoundingMode.HALF_UP);
        this.sbc = wage.sbc();
        this.fsrSource = FsrSource.COMPUTED;
        this.fsrMismatch = differs(fsr, importedFsr, FSR_TOLERANCE)
                || differs(realWage(), importedRealWage, REAL_WAGE_TOLERANCE);
    }

    private static boolean differs(BigDecimal value, BigDecimal imported, BigDecimal tolerance) {
        return imported != null && value.subtract(imported).abs().compareTo(tolerance) > 0;
    }

    /** Null keeps a value. A typed FSR becomes {@code MANUAL}. Everything is checked before anything changes. */
    public void revise(BigDecimal nominalWage, BigDecimal fsr, BigDecimal perDiem, BigDecimal mealsLodging,
                       BigDecimal funeralExpenses, BigDecimal otherBenefits) {
        BigDecimal newWage = nominalWage == null ? this.nominalWage : amount(nominalWage, "laborDetail.nominalWage");
        BigDecimal newFsr = fsr == null ? this.fsr : manualFsr(fsr);
        BigDecimal newPerDiem = perDiem == null ? this.perDiem : amount(perDiem, "laborDetail.perDiem");
        BigDecimal newMeals = mealsLodging == null ? this.mealsLodging
                : amount(mealsLodging, "laborDetail.mealsLodging");
        BigDecimal newFuneral = funeralExpenses == null ? this.funeralExpenses
                : amount(funeralExpenses, "laborDetail.funeralExpenses");
        BigDecimal newOther = otherBenefits == null ? this.otherBenefits
                : amount(otherBenefits, "laborDetail.otherBenefits");
        this.nominalWage = newWage;
        if (fsr != null) {
            this.fsr = newFsr;
            this.fsrSource = FsrSource.MANUAL;
        }
        this.perDiem = newPerDiem;
        this.mealsLodging = newMeals;
        this.funeralExpenses = newFuneral;
        this.otherBenefits = newOther;
    }

    /** {@code round(nominalWage * fsr, 2) + perDiem + mealsLodging + funeralExpenses + otherBenefits}. */
    public BigDecimal realWage() {
        return nominalWage.multiply(fsr).setScale(2, RoundingMode.HALF_UP)
                .add(perDiem).add(mealsLodging).add(funeralExpenses).add(otherBenefits);
    }

    private static BigDecimal manualFsr(BigDecimal fsr) {
        String field = "laborDetail.fsr";
        if (fsr == null) {
            throw new PricingRuleException(field, "REQUIRED", "FSR is required without complete FASAR parameters");
        }
        if (fsr.compareTo(BigDecimal.ONE) < 0 || fsr.compareTo(FSR_MAX) > 0) {
            throw new PricingRuleException(field, "INVALID", "FSR must be between 1 and 3");
        }
        if (fsr.stripTrailingZeros().scale() > FSR_DECIMALS) {
            throw new PricingRuleException(field, "TOO_MANY_DECIMALS", "FSR must have at most eight decimals");
        }
        return fsr.setScale(FSR_DECIMALS, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal benefit(BigDecimal value, String field) {
        return value == null ? BigDecimal.ZERO.setScale(2) : amount(value, field);
    }

    /** A daily amount: zero or more, at most two decimals, below 10^10. */
    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            throw new PricingRuleException(field, "REQUIRED", "Nominal wage is required");
        }
        if (value.signum() < 0) {
            throw new PricingRuleException(field, "INVALID", "Amount cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new PricingRuleException(field, "TOO_MANY_DECIMALS", "Amount must have at most two decimals");
        }
        if (value.compareTo(AMOUNT_LIMIT) >= 0) {
            throw new PricingRuleException(field, "INVALID", "Amount is too large");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}

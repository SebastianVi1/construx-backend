package org.andre.construx.modules.pricing.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * The FSR of a Labor category from the FASAR parameters (docs/pricing.md §5.2, RLOPSRM art. 190-191, normal
 * 8-hour day, no overtime). Pure: the parameters are assumed valid except for the working days, which must
 * leave {@code Tl > 0}. Tp/Tl, FSBC and Ps are kept at eight decimals; only the FSR is rounded to
 * {@code factorRounding}.
 */
public final class FasarCalculator {
    private static final MathContext DIVISION = new MathContext(20);
    private static final int FACTOR_DECIMALS = 8;

    public record Rate(BigDecimal employer, BigDecimal worker) {}

    /** Day counts of a year; every one but {@code workedSundays} and {@code bonus} is a day not worked. */
    public record Days(BigDecimal calendar, BigDecimal sundays, BigDecimal workedSundays, BigDecimal vacation,
                       BigDecimal mondays, BigDecimal holidays, BigDecimal weather, BigDecimal custom,
                       BigDecimal leaveSick, BigDecimal union, BigDecimal bonus) {}

    public record Parameters(BigDecimal uma, boolean sumEmployerAndWorker, Days days, BigDecimal vacationPremiumPct,
                             BigDecimal sundayPremiumPct, BigDecimal otherChargesPct, BigDecimal excessThresholdUma,
                             BigDecimal sbcCapUma, int factorRounding, Map<FasarConcept, Rate> contributions) {}

    /** Paid days, worked days, their ratio and the factor of the contribution base. */
    public record Derived(BigDecimal tp, BigDecimal tl, BigDecimal tpOverTl, BigDecimal fsbc) {}

    /** The factors of one nominal wage; {@code sbc} is rounded to two decimals for display. */
    public record Wage(BigDecimal fsbc, BigDecimal sbc, BigDecimal ps, BigDecimal fsr) {}

    private final Parameters p;
    private final Derived derived;

    public FasarCalculator(Parameters parameters) {
        this.p = parameters;
        Days d = parameters.days();
        BigDecimal tp = d.calendar().add(d.bonus())
                .add(d.vacation().multiply(parameters.vacationPremiumPct()))
                .add(d.workedSundays().multiply(parameters.sundayPremiumPct()));
        BigDecimal tl = d.calendar().subtract(d.sundays().add(d.vacation()).add(d.mondays()).add(d.holidays())
                .add(d.weather()).add(d.custom()).add(d.leaveSick()).add(d.union()));
        if (tl.signum() <= 0) {
            throw new PricingRuleException("days", "INVALID", "The days not worked leave no working days");
        }
        if (d.calendar().signum() <= 0) {
            throw new PricingRuleException("days.calendar", "INVALID", "Calendar days must be greater than zero");
        }
        this.derived = new Derived(tp, tl, factor(tp.divide(tl, DIVISION)), factor(tp.divide(d.calendar(), DIVISION)));
    }

    public Derived derived() {
        return derived;
    }

    public Wage wage(BigDecimal nominalWage) {
        BigDecimal uma = p.uma();
        BigDecimal sbc = nominalWage.multiply(derived.fsbc()).min(p.sbcCapUma().multiply(uma));
        BigDecimal ps = BigDecimal.ZERO;
        if (sbc.signum() > 0) {
            BigDecimal sp = BigDecimal.ZERO;
            for (var entry : p.contributions().entrySet()) {
                sp = sp.add(rate(entry.getValue()).multiply(base(entry.getKey(), sbc)));
            }
            ps = factor(sp.divide(sbc, DIVISION));
        }
        BigDecimal tpOverTl = derived.tpOverTl();
        BigDecimal fsr = ps.multiply(tpOverTl).add(tpOverTl).add(p.otherChargesPct())
                .setScale(p.factorRounding(), RoundingMode.HALF_UP);
        return new Wage(derived.fsbc(), sbc.setScale(2, RoundingMode.HALF_UP), ps, fsr);
    }

    /** What a concept's rate applies to; other charges are no cuota, they enter the FSR directly. */
    private BigDecimal base(FasarConcept concept, BigDecimal sbc) {
        return switch (concept) {
            case FIXED_FEE -> p.uma();
            case EXCESS_3_UMA -> sbc.subtract(p.excessThresholdUma().multiply(p.uma())).max(BigDecimal.ZERO);
            case OTHER_CHARGES -> BigDecimal.ZERO;
            default -> sbc;
        };
    }

    private BigDecimal rate(Rate rate) {
        return p.sumEmployerAndWorker() ? rate.employer().add(rate.worker()) : rate.employer();
    }

    private static BigDecimal factor(BigDecimal value) {
        return value.setScale(FACTOR_DECIMALS, RoundingMode.HALF_UP);
    }
}

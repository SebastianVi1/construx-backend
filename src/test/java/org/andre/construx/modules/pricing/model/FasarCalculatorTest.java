package org.andre.construx.modules.pricing.model;

import org.andre.construx.modules.pricing.model.FasarCalculator.Days;
import org.andre.construx.modules.pricing.model.FasarCalculator.Parameters;
import org.andre.construx.modules.pricing.model.FasarCalculator.Rate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The FASAR computation of docs/pricing.md §5.2. The parameters are a synthetic set (365 calendar days, 15 days
 * of aguinaldo, 6 vacation days at 25 %, 52 rest Sundays, 7.5 holidays, 6 weather, 2 custom and 3 sick days;
 * UMA 86.88). Expected values are worked out by hand from those parameters:
 * Tp = 365 + 15 + 6 × 0.25 = 381.5; Tl = 365 − (52 + 6 + 7.5 + 6 + 2 + 3) = 288.5;
 * Tp/Tl = 1.32235702; FSBC = 381.5 / 365 = 1.04520548.
 * For Sn = 400: SBC = 418.08219200; the cuotas are rate × SBC except the fixed fee (0.204 × 86.88) and the
 * excess over 3 UMA (0.015 × (SBC − 260.64)); Ps = SP / SBC = 0.32417866; FSR = Ps × Tp/Tl + Tp/Tl.
 */
class FasarCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Days days(String workedSundays) {
        return new Days(d("365"), d("52"), d(workedSundays), d("6"), d("0"), d("7.5"), d("6"), d("2"), d("3"),
                d("0"), d("15"));
    }

    private static Map<FasarConcept, Rate> rates() {
        Map<FasarConcept, Rate> rates = new EnumMap<>(FasarConcept.class);
        rates.put(FasarConcept.RISK, new Rate(d("0.0758875"), d("0")));
        rates.put(FasarConcept.FIXED_FEE, new Rate(d("0.204"), d("0")));
        rates.put(FasarConcept.EXCESS_3_UMA, new Rate(d("0.011"), d("0.004")));
        rates.put(FasarConcept.CASH_BENEFITS, new Rate(d("0.007"), d("0.0025")));
        rates.put(FasarConcept.PENSIONERS_MEDICAL, new Rate(d("0.0105"), d("0.00375")));
        rates.put(FasarConcept.DISABILITY_LIFE, new Rate(d("0.0175"), d("0.00625")));
        rates.put(FasarConcept.RETIREMENT, new Rate(d("0.02"), d("0")));
        rates.put(FasarConcept.UNEMPLOYMENT_OLD_AGE, new Rate(d("0.0315"), d("0.01125")));
        rates.put(FasarConcept.DAYCARE, new Rate(d("0.01"), d("0")));
        rates.put(FasarConcept.INFONAVIT, new Rate(d("0.05"), d("0")));
        rates.put(FasarConcept.PAYROLL_TAX, new Rate(d("0.03"), d("0")));
        rates.put(FasarConcept.OTHER_CHARGES, new Rate(d("0.01"), d("0")));
        return rates;
    }

    private static Parameters parameters(Days days, boolean sumEmployerAndWorker, int factorRounding,
                                         String otherChargesPct) {
        return new Parameters(d("86.88"), sumEmployerAndWorker, days, d("0.25"), d("0.25"), d(otherChargesPct),
                d("3"), d("25"), factorRounding, rates());
    }

    private static FasarCalculator standard() {
        return new FasarCalculator(parameters(days("0"), true, 6, "0"));
    }

    @Test
    void theSundayPremiumIsEarnedOnlyOnWorkedSundays() {
        var derived = standard().derived();

        assertThat(derived.tp()).isEqualByComparingTo("381.5");
        assertThat(derived.tl()).isEqualByComparingTo("288.5");
        assertThat(derived.tpOverTl()).isEqualByComparingTo("1.32235702");
        assertThat(derived.fsbc()).isEqualByComparingTo("1.04520548");
        assertThat(derived.fsbc().setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("1.045205");
    }

    @Test
    void workedSundaysAddTheirPremiumDays() {
        var derived = new FasarCalculator(parameters(days("52"), true, 6, "0")).derived();

        // 381.5 + 52 × 0.25 = 394.5; 394.5 / 365 and 394.5 / 288.5 at eight decimals.
        assertThat(derived.tp()).isEqualByComparingTo("394.5");
        assertThat(derived.fsbc()).isEqualByComparingTo("1.08082192");
        assertThat(derived.tpOverTl()).isEqualByComparingTo("1.36741768");
    }

    @Test
    void aWageGetsItsSbcPsAndFsrRoundedToTheFactorRounding() {
        var wage = standard().wage(d("400.00"));

        assertThat(wage.fsbc()).isEqualByComparingTo("1.04520548");
        assertThat(wage.sbc()).isEqualByComparingTo("418.08");
        assertThat(wage.ps()).isEqualByComparingTo("0.32417866");
        assertThat(wage.fsr()).isEqualByComparingTo("1.751037");
    }

    @Test
    void factorRoundingControlsTheFsrDecimals() {
        var wage = new FasarCalculator(parameters(days("0"), true, 4, "0")).wage(d("400.00"));

        assertThat(wage.fsr()).isEqualByComparingTo("1.7510");
        assertThat(wage.fsr().scale()).isEqualTo(4);
    }

    @Test
    void withoutSumEmployerAndWorkerOnlyTheEmployerRatesCount() {
        var wage = new FasarCalculator(parameters(days("0"), false, 6, "0")).wage(d("400.00"));

        assertThat(wage.ps()).isEqualByComparingTo("0.29892233");
        assertThat(wage.fsr()).isEqualByComparingTo("1.717639");
    }

    @Test
    void otherChargesEnterTheFsrDirectlyAndTheOtherChargesRateIsNoCuota() {
        var wage = new FasarCalculator(parameters(days("0"), true, 6, "0.02")).wage(d("400.00"));

        assertThat(wage.ps()).isEqualByComparingTo("0.32417866");
        assertThat(wage.fsr()).isEqualByComparingTo("1.771037");
    }

    @Test
    void belowThreeUmaThereIsNoExcessCuota() {
        // SBC = 200 × 1.04520548 = 209.04 < 3 × 86.88 = 260.64.
        var wage = standard().wage(d("200.00"));

        assertThat(wage.sbc()).isEqualByComparingTo("209.04");
        assertThat(wage.ps()).isEqualByComparingTo("0.36092236");
        assertThat(wage.fsr()).isEqualByComparingTo("1.799625");
    }

    @Test
    void theSbcIsCappedAtTwentyFiveUma() {
        var wage = standard().wage(d("5000.00"));

        assertThat(wage.sbc()).isEqualByComparingTo("2172.00");
        assertThat(wage.ps()).isEqualByComparingTo("0.29749750");
        assertThat(wage.fsr()).isEqualByComparingTo("1.715755");
    }

    @Test
    void aZeroWageHasNoContributions() {
        var wage = standard().wage(d("0.00"));

        assertThat(wage.sbc()).isEqualByComparingTo("0");
        assertThat(wage.ps()).isEqualByComparingTo("0");
        assertThat(wage.fsr()).isEqualByComparingTo("1.322357");
    }

    @Test
    void noWorkingDaysLeftIsRefused() {
        Days none = new Days(d("365"), d("365"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"),
                d("15"));

        assertThatThrownBy(() -> new FasarCalculator(parameters(none, true, 6, "0")))
                .isInstanceOf(PricingRuleException.class);
    }
}

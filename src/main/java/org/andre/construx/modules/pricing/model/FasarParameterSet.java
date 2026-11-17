package org.andre.construx.modules.pricing.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.andre.construx.modules.pricing.model.FasarCalculator.Days;
import org.andre.construx.modules.pricing.model.FasarCalculator.Parameters;
import org.andre.construx.modules.pricing.model.FasarCalculator.Rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * The FASAR parameters of an organization (docs/pricing.md §1.10): one set per organization, replaced in place,
 * {@code version} + 1 on every replace. Only a {@code complete} set can compute FSRs.
 */
@Entity
@Table(name = "fasar_parameter_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FasarParameterSet {
    private static final BigDecimal DAYS_LIMIT = new BigDecimal("10000");
    private static final BigDecimal UMA_LIMIT = new BigDecimal("1000000");
    private static final BigDecimal WAGE_LIMIT = new BigDecimal("100000000");
    private static final BigDecimal UMA_COUNT_LIMIT = new BigDecimal("10000");
    private static final int MAX_FACTOR_ROUNDING = 8;

    public enum Source { FASAR_FILE, GENERAL_FIELDS, MANUAL }

    /** Status shown on the Catalog: no set, a set that cannot compute FSRs, a complete one. */
    public enum Status { NONE, PARTIAL, COMPLETE }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Contribution {
        @Column(name = "employer_rate", nullable = false, precision = 10, scale = 8)
        private BigDecimal employerRate;

        @Column(name = "worker_rate", nullable = false, precision = 10, scale = 8)
        private BigDecimal workerRate;

        Contribution(BigDecimal employerRate, BigDecimal workerRate) {
            this.employerRate = employerRate;
            this.workerRate = workerRate;
        }
    }

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "\"year\"")
    private Short year;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal uma;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumWage;

    @Column(nullable = false)
    private boolean sumEmployerAndWorker;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal calendarDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal sundays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal workedSundays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal vacationDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal mondayDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal holidays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal weatherDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal customDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal leaveSickDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal unionDays;

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal bonusDays;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal vacationPremiumPct;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal sundayPremiumPct;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal otherChargesPct;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal excessThresholdUma;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal sbcCapUma;

    @Column(nullable = false)
    private short factorRounding;

    @Column(nullable = false)
    private boolean complete;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant updatedAt;

    private UUID updatedByMembershipId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "fasar_contribution", joinColumns = @JoinColumn(name = "set_id"))
    @MapKeyColumn(name = "concept", length = 40)
    @MapKeyEnumerated(EnumType.STRING)
    private Map<FasarConcept, Contribution> contributions = new EnumMap<>(FasarConcept.class);

    /** A set typed by hand ({@code PUT /fasar}): checked, complete, source {@code MANUAL}. */
    public static FasarParameterSet manual(UUID organizationId, Integer year, BigDecimal minimumWage,
                                           Parameters parameters, Instant at, UUID membershipId) {
        FasarParameterSet set = new FasarParameterSet();
        set.id = UUID.randomUUID();
        set.organizationId = organizationId;
        set.replaceManual(year, minimumWage, parameters, at, membershipId);
        return set;
    }

    /**
     * Replaces every value with a hand-typed complete set. Everything is checked, the working days included
     * ({@code Tl > 0}), before anything changes.
     */
    public void replaceManual(Integer year, BigDecimal minimumWage, Parameters parameters, Instant at,
                              UUID membershipId) {
        Short checkedYear = year(year);
        BigDecimal wage = decimal(minimumWage, "minimumWage", 2, BigDecimal.ZERO, WAGE_LIMIT);
        Parameters p = check(parameters);
        new FasarCalculator(p);
        this.year = checkedYear;
        this.minimumWage = wage;
        this.uma = p.uma();
        this.sumEmployerAndWorker = p.sumEmployerAndWorker();
        Days d = p.days();
        this.calendarDays = d.calendar();
        this.sundays = d.sundays();
        this.workedSundays = d.workedSundays();
        this.vacationDays = d.vacation();
        this.mondayDays = d.mondays();
        this.holidays = d.holidays();
        this.weatherDays = d.weather();
        this.customDays = d.custom();
        this.leaveSickDays = d.leaveSick();
        this.unionDays = d.union();
        this.bonusDays = d.bonus();
        this.vacationPremiumPct = p.vacationPremiumPct();
        this.sundayPremiumPct = p.sundayPremiumPct();
        this.otherChargesPct = p.otherChargesPct();
        this.excessThresholdUma = p.excessThresholdUma();
        this.sbcCapUma = p.sbcCapUma();
        this.factorRounding = (short) p.factorRounding();
        this.contributions.clear();
        p.contributions().forEach((concept, rate) ->
                this.contributions.put(concept, new Contribution(rate.employer(), rate.worker())));
        this.complete = true;
        this.source = Source.MANUAL;
        this.version++;
        this.updatedAt = at;
        this.updatedByMembershipId = membershipId;
    }

    public Status status() {
        return complete ? Status.COMPLETE : Status.PARTIAL;
    }

    /** The calculator of a complete set; a partial set cannot compute FSRs. */
    public FasarCalculator calculator() {
        if (!complete) {
            throw new IllegalStateException("A partial FASAR set cannot compute FSRs");
        }
        return new FasarCalculator(parameters());
    }

    public Parameters parameters() {
        Map<FasarConcept, Rate> rates = new EnumMap<>(FasarConcept.class);
        contributions.forEach((concept, c) -> rates.put(concept, new Rate(c.getEmployerRate(), c.getWorkerRate())));
        return new Parameters(uma, sumEmployerAndWorker,
                new Days(calendarDays, sundays, workedSundays, vacationDays, mondayDays, holidays, weatherDays,
                        customDays, leaveSickDays, unionDays, bonusDays),
                vacationPremiumPct, sundayPremiumPct, otherChargesPct, excessThresholdUma, sbcCapUma,
                factorRounding, rates);
    }

    private static Short year(Integer year) {
        if (year == null) {
            throw new PricingRuleException("year", "REQUIRED", "Year is required");
        }
        if (year < 1900 || year > 2999) {
            throw new PricingRuleException("year", "INVALID", "Year is out of range");
        }
        return year.shortValue();
    }

    /** Every value present and inside its column; rates and percentages are fractions from 0 to 1. */
    private static Parameters check(Parameters p) {
        if (p == null || p.days() == null) {
            throw new PricingRuleException("days", "REQUIRED", "Days are required");
        }
        Days d = p.days();
        Days days = new Days(day(d.calendar(), "days.calendar"), day(d.sundays(), "days.sundays"),
                day(d.workedSundays(), "days.workedSundays"), day(d.vacation(), "days.vacation"),
                day(d.mondays(), "days.mondays"), day(d.holidays(), "days.holidays"),
                day(d.weather(), "days.weather"), day(d.custom(), "days.custom"),
                day(d.leaveSick(), "days.leaveSick"), day(d.union(), "days.union"), day(d.bonus(), "days.bonus"));
        if (days.calendar().signum() <= 0) {
            throw new PricingRuleException("days.calendar", "INVALID", "Calendar days must be greater than zero");
        }
        BigDecimal uma = decimal(p.uma(), "uma", 4, BigDecimal.ZERO, UMA_LIMIT);
        if (uma.signum() <= 0) {
            throw new PricingRuleException("uma", "INVALID", "UMA must be greater than zero");
        }
        BigDecimal sbcCap = decimal(p.sbcCapUma(), "caps.sbcCapUma", 2, BigDecimal.ZERO, UMA_COUNT_LIMIT);
        if (sbcCap.signum() <= 0) {
            throw new PricingRuleException("caps.sbcCapUma", "INVALID", "The SBC cap must be greater than zero");
        }
        if (p.factorRounding() < 0 || p.factorRounding() > MAX_FACTOR_ROUNDING) {
            throw new PricingRuleException("caps.factorRounding", "INVALID", "Factor rounding must be 0 to 8");
        }
        Map<FasarConcept, Rate> rates = new EnumMap<>(FasarConcept.class);
        for (FasarConcept concept : FasarConcept.values()) {
            Rate rate = p.contributions() == null ? null : p.contributions().get(concept);
            String field = "contributions." + concept.name();
            if (rate == null) {
                throw new PricingRuleException(field, "REQUIRED", "Every contribution concept is required");
            }
            rates.put(concept, new Rate(fraction(rate.employer(), field + ".employerRate"),
                    fraction(rate.worker(), field + ".workerRate")));
        }
        return new Parameters(uma, p.sumEmployerAndWorker(), days,
                fraction(p.vacationPremiumPct(), "premiums.vacationPct"),
                fraction(p.sundayPremiumPct(), "premiums.sundayPct"),
                fraction(p.otherChargesPct(), "premiums.otherChargesPct"),
                decimal(p.excessThresholdUma(), "caps.excessThresholdUma", 2, BigDecimal.ZERO, UMA_COUNT_LIMIT),
                sbcCap, p.factorRounding(), rates);
    }

    private static BigDecimal day(BigDecimal value, String field) {
        return decimal(value, field, 3, BigDecimal.ZERO, DAYS_LIMIT);
    }

    private static BigDecimal fraction(BigDecimal value, String field) {
        BigDecimal f = decimal(value, field, 8, BigDecimal.ZERO, null);
        if (f.compareTo(BigDecimal.ONE) > 0) {
            throw new PricingRuleException(field, "INVALID", "A rate is a fraction from 0 to 1");
        }
        return f;
    }

    /** Present, at least {@code min}, below {@code limit} (when given), at most {@code decimals} decimals. */
    private static BigDecimal decimal(BigDecimal value, String field, int decimals, BigDecimal min,
                                      BigDecimal limit) {
        if (value == null) {
            throw new PricingRuleException(field, "REQUIRED", field + " is required");
        }
        if (value.compareTo(min) < 0) {
            throw new PricingRuleException(field, "INVALID", field + " cannot be negative");
        }
        if (limit != null && value.compareTo(limit) >= 0) {
            throw new PricingRuleException(field, "INVALID", field + " is too large");
        }
        if (value.stripTrailingZeros().scale() > decimals) {
            throw new PricingRuleException(field, "TOO_MANY_DECIMALS",
                    field + " must have at most " + decimals + " decimals");
        }
        return value.setScale(decimals, RoundingMode.UNNECESSARY);
    }
}

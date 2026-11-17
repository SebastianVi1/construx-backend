package org.andre.construx.modules.finance.model;

import org.andre.construx.modules.finance.model.LaborAccrual.AccruedDay;
import org.andre.construx.modules.finance.model.LaborAccrual.Cost;
import org.andre.construx.modules.finance.model.LaborAccrual.PersonDay;
import org.andre.construx.modules.finance.model.LaborAccrual.Rate;
import org.andre.construx.modules.finance.model.LaborAccrual.RateKind;
import org.andre.construx.modules.finance.model.LaborAccrual.UnratedReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accrual rule of docs/pricing.md §6.2. Days are in America/Mexico_City (UTC-6 all year since 2022), so the
 * end of 10 March 2026 is 2026-03-11T06:00:00Z.
 */
class LaborAccrualTest {
    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    private static final UUID SITE = UUID.randomUUID();
    private static final UUID OTHER_SITE = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID LUIS = UUID.randomUUID();
    private static final UUID PEON = UUID.randomUUID();
    private static final UUID OFICIAL = UUID.randomUUID();
    private static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);
    private static final LocalDate MARCH_11 = LocalDate.of(2026, 3, 11);
    /** The first instant of 11 March in Mexico City, which is where 10 March ends. */
    private static final Instant END_OF_MARCH_10 = Instant.parse("2026-03-11T06:00:00Z");

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    private static Rate catalog(UUID site, UUID person, UUID category, Instant from) {
        return new Rate(site, person, RateKind.CATALOG, category, null, from);
    }

    private static Rate fixed(UUID site, UUID person, String amount, Instant from) {
        return new Rate(site, person, RateKind.FIXED, null, new BigDecimal(amount), from);
    }

    private static Rate none(UUID site, UUID person, Instant from) {
        return new Rate(site, person, RateKind.NONE, null, null, from);
    }

    private static Cost cost(UUID category, String amount, Instant from) {
        return new Cost(category, new BigDecimal(amount), from);
    }

    private static List<AccruedDay> accrue(List<Rate> rates, List<Cost> costs, PersonDay... days) {
        return new LaborAccrual(MEXICO, rates, costs).accrue(List.of(days));
    }

    private static AccruedDay only(List<AccruedDay> days) {
        assertEquals(1, days.size(), "one accrued day");
        return days.getFirst();
    }

    @Test
    void aCatalogRateAccruesTheCategoryCostOfThatDay() {
        AccruedDay day = only(accrue(
                List.of(catalog(SITE, ANA, PEON, at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "580.00", at("2026-02-01T15:00:00Z")), cost(PEON, "610.00", at("2026-03-05T15:00:00Z")),
                        cost(PEON, "640.00", at("2026-03-12T15:00:00Z"))),
                new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("610.00"), day.amount());
        assertFalse(day.unrated());
        assertNull(day.unratedReason());
        assertEquals(RateKind.CATALOG, day.rate().kind());
        assertEquals(PEON, day.rate().laborCategoryId());
    }

    @Test
    void aFixedRateAccruesItsDailyAmountWhateverTheCatalogSays() {
        AccruedDay day = only(accrue(
                List.of(fixed(SITE, ANA, "650.5", at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "610.00", at("2026-03-01T15:00:00Z"))),
                new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("650.50"), day.amount());
        assertFalse(day.unrated());
        assertEquals(RateKind.FIXED, day.rate().kind());
    }

    @Test
    void withoutAnyRateTheDayCountsZeroAndIsFlaggedNoRate() {
        AccruedDay day = only(accrue(List.of(), List.of(), new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("0.00"), day.amount());
        assertTrue(day.unrated());
        assertEquals(UnratedReason.NO_RATE, day.unratedReason());
        assertNull(day.rate());
    }

    @Test
    void aNoneRateCountsZeroAndIsFlaggedNoRate() {
        AccruedDay day = only(accrue(
                List.of(fixed(SITE, ANA, "650.00", at("2026-03-01T15:00:00Z")), none(SITE, ANA, at("2026-03-09T15:00:00Z"))),
                List.of(), new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("0.00"), day.amount());
        assertEquals(UnratedReason.NO_RATE, day.unratedReason());
        assertEquals(RateKind.NONE, day.rate().kind());
    }

    @Test
    void aCatalogRateWhoseCategoryHasNoCostYetCountsZeroAndIsFlaggedNoCostYet() {
        AccruedDay day = only(accrue(
                List.of(catalog(SITE, ANA, PEON, at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "610.00", END_OF_MARCH_10)),
                new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("0.00"), day.amount());
        assertEquals(UnratedReason.NO_COST_YET, day.unratedReason());
        assertEquals(PEON, day.rate().laborCategoryId());
    }

    @Test
    void aRateSetLateInTheDayAppliesToThatDayButNotTheDayBefore() {
        // 23:59 on 10 March in Mexico City is 05:59 UTC on 11 March.
        List<AccruedDay> days = accrue(
                List.of(fixed(SITE, ANA, "700.00", at("2026-03-11T05:59:59Z"))), List.of(),
                new PersonDay(SITE, ANA, MARCH_10), new PersonDay(SITE, ANA, LocalDate.of(2026, 3, 9)));

        assertEquals(new BigDecimal("700.00"), dayOf(days, ANA, MARCH_10).amount());
        assertEquals(UnratedReason.NO_RATE, dayOf(days, ANA, LocalDate.of(2026, 3, 9)).unratedReason());
    }

    @Test
    void aRateOrCostEffectiveExactlyAtTheEndOfTheDayStartsTheNextDay() {
        List<AccruedDay> days = accrue(
                List.of(fixed(SITE, ANA, "600.00", at("2026-03-01T15:00:00Z")), fixed(SITE, ANA, "700.00", END_OF_MARCH_10),
                        catalog(SITE, LUIS, PEON, at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "500.00", at("2026-03-01T15:00:00Z")), cost(PEON, "520.00", END_OF_MARCH_10)),
                new PersonDay(SITE, ANA, MARCH_10), new PersonDay(SITE, ANA, MARCH_11),
                new PersonDay(SITE, LUIS, MARCH_10), new PersonDay(SITE, LUIS, MARCH_11));

        assertEquals(new BigDecimal("600.00"), dayOf(days, ANA, MARCH_10).amount());
        assertEquals(new BigDecimal("700.00"), dayOf(days, ANA, MARCH_11).amount());
        assertEquals(new BigDecimal("500.00"), dayOf(days, LUIS, MARCH_10).amount());
        assertEquals(new BigDecimal("520.00"), dayOf(days, LUIS, MARCH_11).amount());
    }

    @Test
    void severalRateChangesTheSameDayLeaveTheLastOneOfThatDay() {
        AccruedDay day = only(accrue(
                List.of(fixed(SITE, ANA, "600.00", at("2026-03-10T15:00:00Z")),
                        catalog(SITE, ANA, OFICIAL, at("2026-03-10T18:00:00Z")),
                        fixed(SITE, ANA, "650.00", at("2026-03-10T16:00:00Z"))),
                List.of(cost(OFICIAL, "800.00", at("2026-03-01T15:00:00Z"))),
                new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("800.00"), day.amount());
        assertEquals(OFICIAL, day.rate().laborCategoryId());
    }

    @Test
    void aCostRecordedLaterTheSameDayRepricesThatDayOnly() {
        List<AccruedDay> days = accrue(
                List.of(catalog(SITE, ANA, PEON, at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "610.00", at("2026-03-01T15:00:00Z")), cost(PEON, "625.00", at("2026-03-10T23:00:00Z"))),
                new PersonDay(SITE, ANA, LocalDate.of(2026, 3, 9)), new PersonDay(SITE, ANA, MARCH_10));

        assertEquals(new BigDecimal("610.00"), dayOf(days, ANA, LocalDate.of(2026, 3, 9)).amount());
        assertEquals(new BigDecimal("625.00"), dayOf(days, ANA, MARCH_10).amount());
    }

    @Test
    void aDayOnTwoSitesIsOneJornadaOnEachAtThatSitesRate() {
        List<AccruedDay> days = accrue(
                List.of(fixed(SITE, ANA, "600.00", at("2026-03-01T15:00:00Z")),
                        fixed(OTHER_SITE, ANA, "750.00", at("2026-03-01T15:00:00Z"))),
                List.of(), new PersonDay(SITE, ANA, MARCH_10), new PersonDay(OTHER_SITE, ANA, MARCH_10));

        assertEquals(2, days.size());
        assertEquals(new BigDecimal("600.00"), days.stream().filter(d -> d.projectId().equals(SITE)).findFirst()
                .orElseThrow().amount());
        assertEquals(new BigDecimal("750.00"), days.stream().filter(d -> d.projectId().equals(OTHER_SITE)).findFirst()
                .orElseThrow().amount());
    }

    @Test
    void aRateOnAnotherSiteIsIgnored() {
        AccruedDay day = only(accrue(
                List.of(fixed(OTHER_SITE, ANA, "750.00", at("2026-03-01T15:00:00Z")),
                        fixed(SITE, LUIS, "750.00", at("2026-03-01T15:00:00Z"))),
                List.of(), new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(UnratedReason.NO_RATE, day.unratedReason());
    }

    @Test
    void severalCheckInsTheSameDayAreOneJornada() {
        AccruedDay day = only(accrue(List.of(fixed(SITE, ANA, "600.00", at("2026-03-01T15:00:00Z"))), List.of(),
                new PersonDay(SITE, ANA, MARCH_10), new PersonDay(SITE, ANA, MARCH_10)));

        assertEquals(new BigDecimal("600.00"), day.amount());
    }

    @Test
    void aDayWithoutACheckInAccruesNothing() {
        // A removed Check-in yields no person-day, so only the other day accrues.
        List<AccruedDay> days = accrue(List.of(fixed(SITE, ANA, "600.00", at("2026-03-01T15:00:00Z"))), List.of(),
                new PersonDay(SITE, ANA, MARCH_11));

        assertEquals(1, days.size());
        assertEquals(MARCH_11, days.getFirst().day());
        assertEquals(new BigDecimal("600.00"), LaborAccrual.total(days));
    }

    @Test
    void theTotalSumsEveryDayIncludingUnratedOnesAtZero() {
        List<AccruedDay> days = accrue(
                List.of(fixed(SITE, ANA, "600.00", at("2026-03-01T15:00:00Z")),
                        catalog(SITE, LUIS, PEON, at("2026-03-01T15:00:00Z"))),
                List.of(cost(PEON, "610.00", at("2026-03-10T20:00:00Z"))),
                new PersonDay(SITE, ANA, MARCH_10), new PersonDay(SITE, ANA, MARCH_11),
                new PersonDay(SITE, LUIS, LocalDate.of(2026, 3, 9)), new PersonDay(SITE, LUIS, MARCH_10));

        assertEquals(4, days.size());
        assertEquals(new BigDecimal("1810.00"), LaborAccrual.total(days));
        assertEquals(1, days.stream().filter(AccruedDay::unrated).count());
    }

    private static AccruedDay dayOf(List<AccruedDay> days, UUID person, LocalDate day) {
        return days.stream().filter(d -> d.membershipId().equals(person) && d.day().equals(day)).findFirst()
                .orElseThrow();
    }
}

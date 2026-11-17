package org.andre.construx.modules.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Accrued labor cost rule (docs/pricing.md §6.2, ADR 0009), on plain values so it runs without JPA. Every
 * person-day (a Site, a person and a day with at least one Check-in that was not removed) accrues one day at the
 * Labor rate in force for that person on that Site at the end of that day: the cost its Labor category had then
 * ({@code CATALOG}) or the fixed amount ({@code FIXED}). A day without a rate, with a {@code NONE} rate or whose
 * category had no cost yet counts {@code 0.00} and is flagged. "End of day D" is the first instant of D + 1 in the
 * application's time zone; a rate or cost effective exactly then belongs to the next day.
 */
public final class LaborAccrual {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public enum RateKind { CATALOG, FIXED, NONE }

    public enum UnratedReason { NO_RATE, NO_COST_YET }

    /** One jornada of a person on a Site. */
    public record PersonDay(UUID projectId, UUID membershipId, LocalDate day) {}

    /**
     * A Labor rate row: {@code laborCategoryId} for {@code CATALOG}, {@code dailyAmount} for {@code FIXED}.
     */
    public record Rate(UUID projectId, UUID membershipId, RateKind kind, UUID laborCategoryId, BigDecimal dailyAmount,
                       Instant effectiveFrom) {}

    /** A Cost history row of a Labor category. */
    public record Cost(UUID resourceId, BigDecimal amount, Instant effectiveFrom) {}

    /**
     * The accrual of one person-day. {@code rate} is the rate row in force (null without one) and {@code amount}
     * what the day costs, two decimals; {@code unratedReason} is null when the day is rated.
     */
    public record AccruedDay(UUID projectId, UUID membershipId, LocalDate day, Rate rate, BigDecimal amount,
                             UnratedReason unratedReason) {
        public boolean unrated() {
            return unratedReason != null;
        }
    }

    private record Key(UUID projectId, UUID membershipId) {}

    private final ZoneId zone;
    private final Map<Key, List<Rate>> rates;
    private final Map<UUID, List<Cost>> costs;

    public LaborAccrual(ZoneId zone, Collection<Rate> rates, Collection<Cost> costs) {
        this.zone = zone;
        this.rates = rates.stream().sorted(Comparator.comparing(Rate::effectiveFrom))
                .collect(Collectors.groupingBy(r -> new Key(r.projectId(), r.membershipId()), Collectors.toList()));
        this.costs = costs.stream().sorted(Comparator.comparing(Cost::effectiveFrom))
                .collect(Collectors.groupingBy(Cost::resourceId, Collectors.toList()));
    }

    /** One accrued day per distinct person-day, in the order first given. */
    public List<AccruedDay> accrue(Collection<PersonDay> personDays) {
        List<AccruedDay> result = new ArrayList<>();
        for (PersonDay personDay : new LinkedHashSet<>(personDays)) {
            result.add(accrue(personDay));
        }
        return result;
    }

    /** The sum of the days' amounts, two decimals. */
    public static BigDecimal total(Collection<AccruedDay> days) {
        return days.stream().map(AccruedDay::amount).reduce(ZERO, BigDecimal::add);
    }

    private AccruedDay accrue(PersonDay personDay) {
        Instant end = personDay.day().plusDays(1).atStartOfDay(zone).toInstant();
        Rate rate = latestBefore(rates.getOrDefault(new Key(personDay.projectId(), personDay.membershipId()), List.of()),
                Rate::effectiveFrom, end);
        if (rate == null || rate.kind() == RateKind.NONE) {
            return day(personDay, rate, ZERO, UnratedReason.NO_RATE);
        }
        if (rate.kind() == RateKind.FIXED) {
            return day(personDay, rate, money(rate.dailyAmount()), null);
        }
        Cost cost = latestBefore(costs.getOrDefault(rate.laborCategoryId(), List.of()), Cost::effectiveFrom, end);
        return cost == null ? day(personDay, rate, ZERO, UnratedReason.NO_COST_YET)
                : day(personDay, rate, money(cost.amount()), null);
    }

    /** The row with the greatest {@code effectiveFrom < end} of a list sorted oldest first. */
    private static <T> T latestBefore(List<T> sorted, java.util.function.Function<T, Instant> effectiveFrom,
                                      Instant end) {
        T found = null;
        for (T row : sorted) {
            if (!effectiveFrom.apply(row).isBefore(end)) {
                break;
            }
            found = row;
        }
        return found;
    }

    private static AccruedDay day(PersonDay personDay, Rate rate, BigDecimal amount, UnratedReason reason) {
        return new AccruedDay(personDay.projectId(), personDay.membershipId(), personDay.day(), rate, amount, reason);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}

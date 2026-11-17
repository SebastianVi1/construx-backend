package org.andre.construx.modules.finance.service;

import org.andre.construx.modules.attendance.service.AttendanceService;
import org.andre.construx.modules.finance.model.LaborAccrual;
import org.andre.construx.modules.finance.model.LaborAccrual.AccruedDay;
import org.andre.construx.modules.finance.model.LaborAccrual.PersonDay;
import org.andre.construx.modules.finance.model.LaborAccrual.Rate;
import org.andre.construx.modules.finance.model.LaborAccrual.RateKind;
import org.andre.construx.modules.finance.model.LaborAccrual.UnratedReason;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.pricing.service.ResourceDirectory;
import org.andre.construx.modules.pricing.service.ResourceDirectory.CatalogResource;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.service.LaborRateService;
import org.andre.construx.modules.project.service.LaborRateService.RateRow;
import org.andre.construx.shared.time.LocalDay;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Accrued labor cost of a Site (docs/pricing.md §6.2-§6.3, ADR 0009): gathers the person-days
 * (attendance), the Labor rate rows (project) and the Cost history of their categories (pricing) and hands them to
 * {@link LaborAccrual}. Nothing is stored: every read computes it again. Reads need {@code VIEW_BUDGET}.
 */
@Service
public class LaborCostService {
    /** The longest range a read accepts, in days, both ends included. */
    public static final int MAX_RANGE_DAYS = 366;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    private final AttendanceService attendance;
    private final LaborRateService laborRates;
    private final ResourceDirectory catalog;
    private final MembershipDirectory memberships;
    private final PersonDirectory people;
    private final LocalDay localDay;

    public LaborCostService(AttendanceService attendance, LaborRateService laborRates, ResourceDirectory catalog,
                            MembershipDirectory memberships, PersonDirectory people, LocalDay localDay) {
        this.attendance = attendance;
        this.laborRates = laborRates;
        this.catalog = catalog;
        this.memberships = memberships;
        this.people = people;
        this.localDay = localDay;
    }

    public record CategoryRef(UUID id, String code, String name) {}

    /**
     * A rate as the accrual shows it. {@code laborCategory} for {@code CATALOG}; {@code dailyAmount} is the
     * amount of that day (or, for a current rate, of today), null for {@code NONE} or a category without a cost.
     */
    public record RateView(RateKind kind, CategoryRef laborCategory, String dailyAmount) {}

    /** One person's accrual over the range; {@code currentRate} is null when they never had a rate on the Site. */
    public record PersonCost(UUID membershipId, String fullName, int days, String amount, int unratedDays,
                             RateView currentRate) {}

    public record LaborCostReport(LocalDate from, LocalDate to, String total, int personDays, int unratedPersonDays,
                                  List<PersonCost> people) {}

    /** One person-day; {@code rate} is null when the person had no rate row that day. */
    public record DayRow(LocalDate day, UUID membershipId, String fullName, RateView rate, String amount,
                         boolean unrated, UnratedReason unratedReason) {}

    public record DayPage(List<DayRow> content, int page, int size, long totalElements, int totalPages) {}

    /** Totals and per-person accrual of the Site over {@code [from, to]} (defaults in {@link #range}). */
    @Transactional(readOnly = true)
    public LaborCostReport report(SiteScope site, LocalDate from, LocalDate to) {
        site.require(PermissionAction.VIEW_BUDGET);
        Range range = range(site, from, to);
        List<AccruedDay> days = accrue(site.organizationId(),
                attendance.personDays(site.siteId(), range.from(), range.to()), List.of(site.siteId()));
        Names names = names(site.organizationId(), days);
        Map<UUID, RateView> current = currentRates(site);

        Map<UUID, List<AccruedDay>> byPerson = days.stream()
                .collect(Collectors.groupingBy(AccruedDay::membershipId, LinkedHashMap::new, Collectors.toList()));
        List<PersonCost> perPerson = new ArrayList<>();
        byPerson.forEach((membershipId, own) -> perPerson.add(new PersonCost(membershipId, names.of(membershipId),
                own.size(), money(LaborAccrual.total(own)), (int) own.stream().filter(AccruedDay::unrated).count(),
                current.get(membershipId))));
        perPerson.sort(Comparator.comparing((PersonCost p) -> new BigDecimal(p.amount())).reversed()
                .thenComparing(PersonCost::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(PersonCost::membershipId));
        return new LaborCostReport(range.from(), range.to(), money(LaborAccrual.total(days)), days.size(),
                (int) days.stream().filter(AccruedDay::unrated).count(), List.copyOf(perPerson));
    }

    /**
     * The person-days of the Site over {@code [from, to]}, optionally of one person, newest day first and then by
     * name; {@code page} from 0, {@code size} 1..200 (default 50).
     */
    @Transactional(readOnly = true)
    public DayPage days(SiteScope site, UUID membershipId, LocalDate from, LocalDate to, Integer page, Integer size) {
        site.require(PermissionAction.VIEW_BUDGET);
        int number = page == null ? 0 : page;
        int length = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must be 0 or more");
        }
        if (length < 1 || length > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Range range = range(site, from, to);
        List<AccruedDay> days = accrue(site.organizationId(),
                attendance.personDays(site.siteId(), range.from(), range.to()).stream()
                        .filter(d -> membershipId == null || d.membershipId().equals(membershipId)).toList(),
                List.of(site.siteId()));
        Names names = names(site.organizationId(), days);
        Map<UUID, CatalogResource> categories = categories(site.organizationId(), days);
        List<DayRow> rows = days.stream()
                .map(d -> new DayRow(d.day(), d.membershipId(), names.of(d.membershipId()), dayRate(d, categories),
                        money(d.amount()), d.unrated(), d.unratedReason()))
                .sorted(Comparator.comparing(DayRow::day).reversed()
                        .thenComparing(DayRow::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(DayRow::membershipId))
                .toList();
        int fromIndex = (int) Math.min((long) number * length, rows.size());
        int toIndex = Math.min(fromIndex + length, rows.size());
        int totalPages = (rows.size() + length - 1) / length;
        return new DayPage(rows.subList(fromIndex, toIndex), number, length, rows.size(), totalPages);
    }

    /**
     * Every accrued day of these Sites of the organization over their whole life, for budgets and organization
     * finance (docs/pricing.md §6.4). No permission check: callers authorize.
     */
    @Transactional(readOnly = true)
    public List<AccruedDay> accrued(UUID organizationId, Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return accrue(organizationId, attendance.personDays(projectIds), projectIds);
    }

    /** The Site's accrued labor cost over its whole life, two decimals. No permission check. */
    @Transactional(readOnly = true)
    public BigDecimal total(UUID organizationId, UUID projectId) {
        return LaborAccrual.total(accrued(organizationId, List.of(projectId)));
    }

    private List<AccruedDay> accrue(UUID organizationId, List<AttendanceService.PersonDay> personDays,
                                    Collection<UUID> projectIds) {
        if (personDays.isEmpty()) {
            return List.of();
        }
        List<Rate> rates = rates(organizationId, projectIds);
        Set<UUID> categoryIds = rates.stream().map(Rate::laborCategoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<LaborAccrual.Cost> costs = catalog.costHistory(organizationId, categoryIds).stream()
                .map(c -> new LaborAccrual.Cost(c.resourceId(), c.amount(), c.effectiveFrom())).toList();
        return new LaborAccrual(localDay.zone(), rates, costs).accrue(personDays.stream()
                .map(d -> new PersonDay(d.projectId(), d.membershipId(), d.day())).toList());
    }

    private List<Rate> rates(UUID organizationId, Collection<UUID> projectIds) {
        return laborRates.history(organizationId, projectIds).stream().map(LaborCostService::rate).toList();
    }

    private static Rate rate(RateRow row) {
        return new Rate(row.projectId(), row.membershipId(), RateKind.valueOf(row.kind().name()),
                row.laborCategoryId(), row.dailyAmount(), row.effectiveFrom());
    }

    /** The latest rate row of each person on the Site, as it stands now. */
    private Map<UUID, RateView> currentRates(SiteScope site) {
        Map<UUID, RateRow> latest = new HashMap<>();
        for (RateRow row : laborRates.history(site.organizationId(), List.of(site.siteId()))) {
            latest.merge(row.membershipId(), row,
                    (a, b) -> a.effectiveFrom().isAfter(b.effectiveFrom()) ? a : b);
        }
        Map<UUID, CatalogResource> categories = catalog.byIds(site.organizationId(), latest.values().stream()
                .map(RateRow::laborCategoryId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, RateView> views = new HashMap<>();
        latest.forEach((membershipId, row) -> {
            CatalogResource category = row.laborCategoryId() == null ? null : categories.get(row.laborCategoryId());
            BigDecimal amount = switch (row.kind()) {
                case CATALOG -> category == null ? null : category.currentCost();
                case FIXED -> row.dailyAmount();
                case NONE -> null;
            };
            views.put(membershipId, new RateView(RateKind.valueOf(row.kind().name()), ref(category), money(amount)));
        });
        return views;
    }

    private Map<UUID, CatalogResource> categories(UUID organizationId, List<AccruedDay> days) {
        return catalog.byIds(organizationId, days.stream().map(AccruedDay::rate).filter(Objects::nonNull)
                .map(Rate::laborCategoryId).filter(Objects::nonNull).distinct().toList());
    }

    private static RateView dayRate(AccruedDay day, Map<UUID, CatalogResource> categories) {
        Rate rate = day.rate();
        if (rate == null) {
            return null;
        }
        CatalogResource category = rate.laborCategoryId() == null ? null : categories.get(rate.laborCategoryId());
        boolean priced = rate.kind() != RateKind.NONE && day.unratedReason() == null;
        return new RateView(rate.kind(), ref(category), priced ? money(day.amount()) : null);
    }

    private static CategoryRef ref(CatalogResource category) {
        return category == null ? null : new CategoryRef(category.id(), category.code(), category.name());
    }

    private record Range(LocalDate from, LocalDate to) {}

    /**
     * {@code to} defaults to today and {@code from} to the Site's first Check-in day, but never earlier than the
     * longest accepted range before {@code to} (nor later than {@code to}); without Check-ins, to {@code to}.
     * {@code from > to} or more than {@value #MAX_RANGE_DAYS} days is a 400.
     */
    private Range range(SiteScope site, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? localDay.today() : to;
        LocalDate start = from;
        if (start == null) {
            LocalDate earliest = end.minusDays(MAX_RANGE_DAYS - 1);
            start = attendance.firstDay(site.siteId())
                    .map(first -> first.isBefore(earliest) ? earliest : first)
                    .filter(first -> !first.isAfter(end))
                    .orElse(end);
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("The range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        return new Range(start, end);
    }

    /** Display names of the people of a batch of days; the profile name, or the email without one. */
    private Names names(UUID organizationId, List<AccruedDay> days) {
        List<OrganizationMembership> members = memberships.byIds(organizationId,
                days.stream().map(AccruedDay::membershipId).distinct().toList());
        Map<UUID, Person> persons = members.isEmpty() ? Map.of()
                : people.byUserId(members.stream().map(m -> m.getUserAccount().getId()).toList());
        Map<UUID, String> byMembership = new HashMap<>();
        for (OrganizationMembership member : members) {
            String fullName = PersonDirectory.of(persons, member.getUserAccount().getId()).fullName();
            byMembership.put(member.getId(),
                    fullName == null || fullName.isBlank() ? member.getUserAccount().getEmail() : fullName);
        }
        return new Names(byMembership);
    }

    private record Names(Map<UUID, String> byMembership) {
        String of(UUID membershipId) {
            return byMembership.get(membershipId);
        }
    }

    static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

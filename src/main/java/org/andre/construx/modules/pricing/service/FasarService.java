package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.CostSource;
import org.andre.construx.modules.pricing.model.FasarCalculator;
import org.andre.construx.modules.pricing.model.FasarCalculator.Parameters;
import org.andre.construx.modules.pricing.model.FasarConcept;
import org.andre.construx.modules.pricing.model.FasarParameterSet;
import org.andre.construx.modules.pricing.model.FsrSource;
import org.andre.construx.modules.pricing.model.LaborCategoryDetail;
import org.andre.construx.modules.pricing.model.PricingRuleException;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ImportStatus;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.repository.CatalogImportRepository;
import org.andre.construx.modules.pricing.repository.FasarParameterSetRepository;
import org.andre.construx.modules.pricing.repository.LaborCategoryDetailRepository;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.service.ResourceService.PersonRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The FASAR parameters of an organization (docs/pricing.md §2.6) and the recomputation of every Labor
 * category's FSR and real wage from them (§5).
 */
@Service
public class FasarService {
    private final FasarParameterSetRepository sets;
    private final LaborCategoryDetailRepository laborDetails;
    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository history;
    private final DerivedCostService derivedCosts;
    private final CatalogService catalog;
    private final ResourceService reads;
    private final CatalogImportRepository imports;

    public FasarService(FasarParameterSetRepository sets, LaborCategoryDetailRepository laborDetails,
                        ResourceRepository resources, ResourceCostHistoryRepository history,
                        DerivedCostService derivedCosts, CatalogService catalog, ResourceService reads,
                        CatalogImportRepository imports) {
        this.sets = sets;
        this.laborDetails = laborDetails;
        this.resources = resources;
        this.history = history;
        this.derivedCosts = derivedCosts;
        this.catalog = catalog;
        this.reads = reads;
        this.imports = imports;
    }

    /** A full replacement: every value is required. */
    public record FasarInput(Integer year, BigDecimal uma, BigDecimal minimumWage, Boolean sumEmployerAndWorker,
                             DaysData days, PremiumsData premiums, CapsData caps,
                             List<ContributionData> contributions) {}

    public record DaysData(BigDecimal calendar, BigDecimal sundays, BigDecimal workedSundays, BigDecimal vacation,
                           BigDecimal mondays, BigDecimal holidays, BigDecimal weather, BigDecimal custom,
                           BigDecimal leaveSick, BigDecimal union, BigDecimal bonus) {}

    public record PremiumsData(BigDecimal vacationPct, BigDecimal sundayPct, BigDecimal otherChargesPct) {}

    public record CapsData(BigDecimal excessThresholdUma, BigDecimal sbcCapUma, Integer factorRounding) {}

    public record ContributionData(String concept, BigDecimal employerRate, BigDecimal workerRate) {}

    public record Days(String calendar, String sundays, String workedSundays, String vacation, String mondays,
                       String holidays, String weather, String custom, String leaveSick, String union,
                       String bonus) {}

    public record Premiums(String vacationPct, String sundayPct, String otherChargesPct) {}

    public record Caps(String excessThresholdUma, String sbcCapUma, int factorRounding) {}

    public record Contribution(FasarConcept concept, String employerRate, String workerRate) {}

    public record Derived(String tp, String tl, String tpOverTl, String fsbc) {}

    public record LaborCategories(long total, long computed, long mismatches) {}

    public record Recomputation(int laborCategories, int realWagesChanged, int workItemsRecomputed) {}

    /** {@code status NONE} leaves every other field null; {@code recomputation} only answers a replace. */
    public record FasarView(FasarParameterSet.Status status, FasarParameterSet.Source source, Integer version,
                            Integer year, String uma, String minimumWage, Boolean sumEmployerAndWorker, Days days,
                            Premiums premiums, Caps caps, List<Contribution> contributions, Derived derived,
                            LaborCategories laborCategories, Instant updatedAt, PersonRef updatedBy,
                            Recomputation recomputation) {}

    @Transactional(readOnly = true)
    public FasarView get(OrgScope org) {
        org.require(PermissionAction.VIEW_PRICES);
        return sets.findByOrganizationId(org.organizationId())
                .map(set -> view(org.organizationId(), set, null))
                .orElseGet(FasarService::none);
    }

    /**
     * Stores a complete {@code MANUAL} set, then recomputes every simple Labor category from it (each one turns
     * {@code COMPUTED}), writes a {@code FASAR} Cost history entry for every changed real wage and recomputes the
     * derived costs, in one transaction.
     */
    @Transactional
    public FasarView replace(OrgScope org, FasarInput input) {
        org.require(PermissionAction.MANAGE_PRICES);
        UUID organizationId = org.organizationId();
        UUID membershipId = org.membership().getId();
        imports.findByActiveOrganizationId(organizationId)
                .filter(job -> job.getStatus() == ImportStatus.IMPORTING)
                .ifPresent(job -> {
                    throw new PricingConflictException("IMPORT_IN_PROGRESS",
                            "The catalog is being imported; try again when the import finishes",
                            Map.of("activeImportId", job.getId()));
                });
        Parameters parameters = parameters(input);
        Instant now = now();
        FasarParameterSet set = sets.findByOrganizationId(organizationId).orElse(null);
        if (set == null) {
            set = FasarParameterSet.manual(organizationId, input.year(), input.minimumWage(), parameters, now,
                    membershipId);
            sets.save(set);
        } else {
            set.replaceManual(input.year(), input.minimumWage(), parameters, now, membershipId);
        }
        catalog.ensureCatalog(organizationId);
        int changed = recompute(organizationId, set.calculator(), now, membershipId);
        int workItems = derivedCosts.recompute(organizationId, now);
        sets.flush();
        return view(organizationId, set, new Recomputation((int) laborDetails.countByOrganizationId(organizationId),
                changed, workItems));
    }

    /**
     * Inside a Catalog import (docs/pricing.md §4.6): replaces the organization's set with the complete one of the
     * FASAR workbook (source {@code FASAR_FILE}, {@code version} + 1). The caller recomputes afterwards.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void replaceFromFile(UUID organizationId, Integer year, BigDecimal minimumWage, Parameters parameters,
                         Instant at, UUID membershipId) {
        FasarParameterSet set = sets.findByOrganizationId(organizationId).orElse(null);
        if (set == null) {
            sets.save(FasarParameterSet.fromFile(organizationId, year, minimumWage, parameters, at, membershipId));
        } else {
            set.replaceFromFile(year, minimumWage, parameters, at, membershipId);
        }
    }

    /**
     * With a complete set, recomputes every simple Labor category of the organization from it (§5.5) and writes a
     * {@code FASAR} Cost history entry for every changed real wage; returns how many changed. Without one, nothing.
     * Derived costs are left to the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int recomputeLaborCategories(UUID organizationId, Instant at, UUID membershipId) {
        return sets.findByOrganizationId(organizationId).filter(FasarParameterSet::isComplete)
                .map(set -> recompute(organizationId, set.calculator(), at, membershipId)).orElse(0);
    }

    private int recompute(UUID organizationId, FasarCalculator fasar, Instant now, UUID membershipId) {
        List<LaborCategoryDetail> details = laborDetails.findByOrganizationId(organizationId);
        Map<UUID, Resource> byId = resources.findAllInOrganization(
                        details.stream().map(LaborCategoryDetail::getResourceId).toList(), organizationId).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
        int changed = 0;
        for (LaborCategoryDetail detail : details) {
            detail.compute(fasar);
            var entry = byId.get(detail.getResourceId()).recordWage(detail, now, membershipId, CostSource.FASAR);
            if (entry != null) {
                history.save(entry);
                changed++;
            }
        }
        return changed;
    }

    /** The calculator of the organization's complete set, empty without one (a partial set cannot compute). */
    @Transactional(readOnly = true)
    public Optional<FasarCalculator> calculator(UUID organizationId) {
        return sets.findByOrganizationId(organizationId)
                .filter(FasarParameterSet::isComplete)
                .map(FasarParameterSet::calculator);
    }

    private static Parameters parameters(FasarInput in) {
        DaysData d = in.days() != null ? in.days()
                : new DaysData(null, null, null, null, null, null, null, null, null, null, null);
        PremiumsData p = in.premiums() != null ? in.premiums() : new PremiumsData(null, null, null);
        CapsData c = in.caps() != null ? in.caps() : new CapsData(null, null, null);
        if (in.sumEmployerAndWorker() == null) {
            throw new PricingRuleException("sumEmployerAndWorker",
                    "REQUIRED", "sumEmployerAndWorker is required");
        }
        if (c.factorRounding() == null) {
            throw new PricingRuleException("caps.factorRounding",
                    "REQUIRED", "Factor rounding is required");
        }
        Map<FasarConcept, FasarCalculator.Rate> rates = new EnumMap<>(FasarConcept.class);
        for (ContributionData row : in.contributions() != null ? in.contributions() : List.<ContributionData>of()) {
            FasarConcept concept = concept(row.concept());
            if (rates.put(concept, new FasarCalculator.Rate(row.employerRate(), row.workerRate())) != null) {
                throw new PricingRuleException("contributions", "INVALID",
                        "Each contribution concept goes once");
            }
        }
        return new Parameters(in.uma(), in.sumEmployerAndWorker(),
                new FasarCalculator.Days(d.calendar(), d.sundays(), d.workedSundays(), d.vacation(), d.mondays(),
                        d.holidays(), d.weather(), d.custom(), d.leaveSick(), d.union(), d.bonus()),
                p.vacationPct(), p.sundayPct(), p.otherChargesPct(), c.excessThresholdUma(), c.sbcCapUma(),
                c.factorRounding(), rates);
    }

    private static FasarConcept concept(String value) {
        return Arrays.stream(FasarConcept.values()).filter(c -> c.name().equals(value)).findFirst()
                .orElseThrow(() -> new PricingRuleException(
                        "contributions.concept", "INVALID", "Unknown contribution concept"));
    }

    private FasarView view(UUID organizationId, FasarParameterSet set, Recomputation recomputation) {
        Parameters p = set.parameters();
        var d = p.days();
        Derived derived = null;
        if (set.isComplete()) {
            var x = set.calculator().derived();
            derived = new Derived(plain(x.tp()), plain(x.tl()), plain(x.tpOverTl()), plain(x.fsbc()));
        }
        List<Contribution> contributions = Arrays.stream(FasarConcept.values())
                .filter(c -> p.contributions().containsKey(c))
                .map(c -> new Contribution(c, plain(p.contributions().get(c).employer()),
                        plain(p.contributions().get(c).worker())))
                .toList();
        LaborCategories labor = new LaborCategories(laborDetails.countByOrganizationId(organizationId),
                laborDetails.countByOrganizationIdAndFsrSource(organizationId, FsrSource.COMPUTED),
                laborDetails.countByOrganizationIdAndFsrMismatchTrue(organizationId));
        return new FasarView(set.status(), set.getSource(), set.getVersion(),
                set.getYear() == null ? null : set.getYear().intValue(), set.getUma().toPlainString(),
                set.getMinimumWage().toPlainString(), set.isSumEmployerAndWorker(),
                new Days(plain(d.calendar()), plain(d.sundays()), plain(d.workedSundays()), plain(d.vacation()),
                        plain(d.mondays()), plain(d.holidays()), plain(d.weather()), plain(d.custom()),
                        plain(d.leaveSick()), plain(d.union()), plain(d.bonus())),
                new Premiums(plain(p.vacationPremiumPct()), plain(p.sundayPremiumPct()), plain(p.otherChargesPct())),
                new Caps(plain(p.excessThresholdUma()), plain(p.sbcCapUma()), p.factorRounding()),
                contributions, derived, labor, set.getUpdatedAt(),
                reads.person(organizationId, set.getUpdatedByMembershipId()), recomputation);
    }

    private static FasarView none() {
        return new FasarView(FasarParameterSet.Status.NONE, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /** A day count, rate or factor without trailing zeros ({@code "7.5"}, {@code "365"}). */
    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

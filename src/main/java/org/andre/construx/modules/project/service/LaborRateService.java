package org.andre.construx.modules.project.service;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.service.ResourceDirectory;
import org.andre.construx.modules.pricing.service.ResourceDirectory.CatalogResource;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.modules.project.model.LaborRateAssignment;
import org.andre.construx.modules.project.model.LaborRateAssignment.Choice;
import org.andre.construx.modules.project.model.LaborRateKind;
import org.andre.construx.modules.project.model.ProjectAccess;
import org.andre.construx.modules.project.repository.LaborRateAssignmentRepository;
import org.andre.construx.modules.project.repository.ProjectAccessRepository;
import org.andre.construx.modules.project.repository.ProjectRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Labor rate of a person on a Site (docs/pricing.md §6.1): set by whoever holds MANAGE_PROJECT on the Site,
 * read by them and by VIEW_BUDGET holders; nobody else sees per-person costs.
 */
@Service
public class LaborRateService {
    private final LaborRateAssignmentRepository rates;
    private final ProjectAccessRepository accesses;
    private final ProjectRepository projects;
    private final ResourceDirectory catalog;
    private final MembershipDirectory memberships;
    private final PersonDirectory people;
    private final Clock clock;

    public LaborRateService(LaborRateAssignmentRepository rates, ProjectAccessRepository accesses,
                            ProjectRepository projects, ResourceDirectory catalog, MembershipDirectory memberships,
                            PersonDirectory people, Clock clock) {
        this.rates = rates;
        this.accesses = accesses;
        this.projects = projects;
        this.catalog = catalog;
        this.memberships = memberships;
        this.people = people;
        this.clock = clock;
    }

    public record Category(UUID id, String code, String name, String realWage) {}

    public record SetBy(UUID membershipId, String fullName) {}

    /** {@code dailyAmount} is the amount in force now: the category's current cost, or the fixed amount. */
    public record LaborRate(LaborRateKind kind, Category laborCategory, String dailyAmount, Instant effectiveFrom,
                            SetBy setBy) {}

    public record ProjectRef(UUID id, String name) {}

    public record Suggested(@JsonUnwrapped LaborRate rate, ProjectRef fromProject) {}

    public record LaborRateView(LaborRate current, Suggested suggested, List<LaborRate> history) {}

    /** Whoever may set a rate can read it, and so can VIEW_BUDGET holders. */
    public boolean canRead(SiteScope site) {
        return site.can(PermissionAction.VIEW_BUDGET) || site.can(PermissionAction.MANAGE_PROJECT);
    }

    /** The current rate of each of these people on the Site; empty when the caller may not read rates. */
    @Transactional(readOnly = true)
    public Map<UUID, LaborRate> visibleCurrent(SiteScope site, Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty() || !canRead(site)) {
            return Map.of();
        }
        List<LaborRateAssignment> current = rates.currentOf(site.organizationId(), site.siteId(), membershipIds);
        Map<UUID, LaborRate> views = views(site.organizationId(), current);
        return current.stream().collect(Collectors.toMap(LaborRateAssignment::getMembershipId,
                r -> views.get(r.getId())));
    }

    @Transactional(readOnly = true)
    public LaborRateView read(SiteScope site, UUID accessId) {
        ProjectAccess access = load(site, accessId);
        if (!canRead(site)) {
            site.require(PermissionAction.MANAGE_PROJECT);
        }
        UUID membershipId = access.getOrganizationMembership().getId();
        List<LaborRateAssignment> history = rates.history(site.organizationId(), site.siteId(), membershipId);
        LaborRateAssignment elsewhere = rates.elsewhere(site.organizationId(), membershipId, site.siteId(),
                PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        List<LaborRateAssignment> all = new ArrayList<>(history);
        if (elsewhere != null) {
            all.add(elsewhere);
        }
        Map<UUID, LaborRate> views = views(site.organizationId(), all);
        Suggested suggested = elsewhere == null ? null : new Suggested(views.get(elsewhere.getId()),
                projects.findByIdAndOrganizationId(elsewhere.getProjectId(), site.organizationId())
                        .map(p -> new ProjectRef(p.getId(), p.getName())).orElse(null));
        return new LaborRateView(history.isEmpty() ? null : views.get(history.getFirst().getId()), suggested,
                history.stream().map(r -> views.get(r.getId())).toList());
    }

    /**
     * Sets the rate effective now; identical to the current one writes nothing. Returns the access's membership id.
     */
    @Transactional
    public UUID set(SiteScope site, UUID accessId, Choice choice) {
        ProjectAccess access = load(site, accessId);
        if (choice.kind() == LaborRateKind.CATALOG) {
            requireLaborCategory(catalog.get(site.organizationId(), choice.laborCategoryId()));
        }
        site.require(PermissionAction.MANAGE_PROJECT);
        UUID membershipId = access.getOrganizationMembership().getId();
        LaborRateAssignment current = rates.history(site.organizationId(), site.siteId(), membershipId).stream()
                .findFirst().orElse(null);
        if (current == null || !current.sameAs(choice)) {
            rates.save(LaborRateAssignment.of(site.organizationId(), site.siteId(), membershipId, choice,
                    clock.instant(), current, site.membershipId()));
        }
        return membershipId;
    }

    private static void requireLaborCategory(CatalogResource resource) {
        if (resource.type() != ResourceType.LABOR || resource.composite() || resource.archived()) {
            throw new IllegalArgumentException("The labor category must be a simple, active Labor resource");
        }
    }

    private ProjectAccess load(SiteScope site, UUID accessId) {
        return accesses.findByIdAndProjectId(accessId, site.siteId())
                .orElseThrow(() -> new ResourceNotFoundException("Site access not found"));
    }

    private Map<UUID, LaborRate> views(UUID organizationId, List<LaborRateAssignment> rows) {
        Map<UUID, CatalogResource> categories = catalog.byIds(organizationId, rows.stream()
                .map(LaborRateAssignment::getLaborCategoryResourceId).filter(Objects::nonNull).distinct().toList());
        List<OrganizationMembership> setters = memberships.byIds(organizationId, rows.stream()
                .map(LaborRateAssignment::getSetByMembershipId).distinct().toList());
        Map<UUID, Person> names = people.byUserId(setters.stream().map(m -> m.getUserAccount().getId()).toList());
        Map<UUID, UUID> userOf = setters.stream().collect(Collectors.toMap(OrganizationMembership::getId,
                m -> m.getUserAccount().getId()));
        return rows.stream().collect(Collectors.toMap(LaborRateAssignment::getId, r -> {
            CatalogResource category = r.getLaborCategoryResourceId() == null ? null
                    : categories.get(r.getLaborCategoryResourceId());
            Category ref = category == null ? null : new Category(category.id(), category.code(), category.name(),
                    money(category.currentCost()));
            BigDecimal daily = switch (r.getKind()) {
                case CATALOG -> category == null ? null : category.currentCost();
                case FIXED -> r.getDailyAmount();
                case NONE -> null;
            };
            UUID setterUser = userOf.get(r.getSetByMembershipId());
            String fullName = setterUser == null ? null : PersonDirectory.of(names, setterUser).fullName();
            return new LaborRate(r.getKind(), ref, money(daily), r.getEffectiveFrom(),
                    new SetBy(r.getSetByMembershipId(), fullName));
        }, (a, b) -> a, LinkedHashMap::new));
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipDirectory;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.CostSource;
import org.andre.construx.modules.pricing.model.Family;
import org.andre.construx.modules.pricing.model.PricingText;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceCostHistory;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads the organization's Catalog of Resources and their Cost history (docs/pricing.md §2.4, §2.5). */
@Service
public class ResourceService {
    static final int DETAIL_HISTORY = 100;
    private static final Set<String> SORT_FIELDS = Set.of("code", "name", "currentCost", "costUpdatedAt");
    private static final Instant EARLIEST = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository history;
    private final MembershipDirectory memberships;
    private final PersonDirectory people;

    public ResourceService(ResourceRepository resources, ResourceCostHistoryRepository history,
                           MembershipDirectory memberships, PersonDirectory people) {
        this.resources = resources;
        this.history = history;
        this.memberships = memberships;
        this.people = people;
    }

    public record UnitRef(UUID id, String code) {}

    public record FamilyRef(UUID id, String code, String name) {}

    public record SupplierRef(UUID id, String name) {}

    public record PersonRef(UUID membershipId, String fullName) {}

    /** Money is a string with two decimals; null until the Resource has a cost. */
    public record ResourceData(UUID id, String code, ResourceType type, String name, UnitRef unit, FamilyRef family,
                               SupplierRef supplier, String brand, boolean isPercentage, boolean isComposite,
                               boolean isHourlyCost, boolean discontinued, String currentCost, Instant costUpdatedAt,
                               CatalogSource source, Instant missingFromImportAt, Instant archivedAt) {}

    public record CostData(UUID id, String amount, Instant effectiveFrom, CostSource source, PersonRef recordedBy,
                           UUID importId, SupplierRef supplier, String sku, String note) {}

    public record UsedIn(long count, List<ResourceRef> sample) {}

    public record ResourceRef(UUID id, String code, String name, ResourceType type) {}

    /**
     * {@code history} is the newest {@value #DETAIL_HISTORY} entries of a simple Resource, null for a composite.
     * The labor, equipment and analysis parts of the contract arrive with their slices.
     */
    public record ResourceDetail(UUID id, String code, ResourceType type, String name, UnitRef unit, FamilyRef family,
                                 SupplierRef supplier, String brand, boolean isPercentage, boolean isComposite,
                                 boolean isHourlyCost, boolean discontinued, String currentCost,
                                 Instant costUpdatedAt, CatalogSource source, Instant missingFromImportAt,
                                 Instant archivedAt, String description, String reference, String sapCode,
                                 String technicalSheet, String procedure, String grouper, String importedCost,
                                 LocalDate importedCostOn, Instant createdAt, Instant updatedAt,
                                 List<CostData> history, UsedIn usedIn) {}

    /** Search criteria of {@code GET /resources}; null means no filter, an empty {@code types} all types. */
    public record ResourceFilter(Collection<ResourceType> types, String query, UUID familyId, UUID unitId,
                                 UUID supplierId, Boolean composite, boolean includeArchived,
                                 boolean missingFromImport, Boolean discontinued) {}

    @Transactional(readOnly = true)
    public CatalogPage<ResourceData> list(OrgScope org, ResourceFilter filter, Integer page, Integer size,
                                          String sort) {
        org.require(PermissionAction.VIEW_PRICES);
        Pageable pageable = CatalogPage.request(page, size, sort, SORT_FIELDS, Sort.by("code"), "code", "id");
        Collection<ResourceType> types = filter.types() == null || filter.types().isEmpty()
                ? EnumSet.allOf(ResourceType.class) : filter.types();
        return CatalogPage.of(resources.search(org.organizationId(), types, filter.includeArchived(),
                filter.missingFromImport(), filter.familyId(), filter.unitId(), filter.supplierId(),
                filter.composite(), filter.discontinued(), pattern(filter.query()), pageable), ResourceService::toData);
    }

    @Transactional(readOnly = true)
    public ResourceDetail get(OrgScope org, UUID resourceId) {
        Resource r = load(org.organizationId(), resourceId);
        org.require(PermissionAction.VIEW_PRICES);
        List<CostData> costs = r.isComposite() ? null : costs(org.organizationId(), history.findNewestFirst(
                org.organizationId(), r.getId(), EARLIEST, LATEST, PageRequest.of(0, DETAIL_HISTORY)).getContent());
        return new ResourceDetail(r.getId(), r.getCode(), r.getType(), r.getName(), unit(r), family(r.getFamily()),
                supplier(r.getSupplier()), r.getBrand(), r.isPercentage(), r.isComposite(), r.isHourlyCost(),
                r.isDiscontinued(), money(r.getCurrentCost()), r.getCostUpdatedAt(), r.getSource(),
                r.getMissingFromImportAt(), r.getArchivedAt(), r.getDescription(), r.getReference(), r.getSapCode(),
                r.getTechnicalSheet(), r.getProcedure(), r.getGrouper(), money(r.getImportedCost()),
                r.getImportedCostOn(), r.getCreatedAt(), r.getUpdatedAt(), costs, new UsedIn(0, List.of()));
    }

    /** The whole Cost history newest first; {@code from}/{@code to} bound {@code effectiveFrom} inclusively. */
    @Transactional(readOnly = true)
    public CatalogPage<CostData> costs(OrgScope org, UUID resourceId, Instant from, Instant to, Integer page,
                                       Integer size) {
        load(org.organizationId(), resourceId);
        org.require(PermissionAction.VIEW_PRICES);
        Pageable pageable = CatalogPage.request(page, size, null, Set.of(), Sort.unsorted());
        var rows = history.findNewestFirst(org.organizationId(), resourceId, from != null ? from : EARLIEST,
                to != null ? to : LATEST, pageable);
        List<CostData> content = costs(org.organizationId(), rows.getContent());
        return new CatalogPage<>(content, rows.getNumber(), rows.getSize(), rows.getTotalElements(),
                rows.getTotalPages());
    }

    /** Resolves a Resource inside the tenant; another organization's id is not found. */
    public Resource load(UUID organizationId, UUID resourceId) {
        return resources.findInOrganization(resourceId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    private List<CostData> costs(UUID organizationId, List<ResourceCostHistory> rows) {
        Set<UUID> authorIds = rows.stream().map(ResourceCostHistory::getRecordedByMembershipId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, OrganizationMembership> authors = memberships.byIds(organizationId, authorIds).stream()
                .collect(Collectors.toMap(OrganizationMembership::getId, Function.identity()));
        Map<UUID, Person> persons = people.byUserId(authors.values().stream()
                .map(m -> m.getUserAccount().getId()).toList());
        return rows.stream().map(h -> new CostData(h.getId(), money(h.getAmount()), h.getEffectiveFrom(),
                h.getSource(), author(authors.get(h.getRecordedByMembershipId()), persons), null,
                supplier(h.getSupplier()), h.getSku(), h.getNote())).toList();
    }

    private static PersonRef author(OrganizationMembership membership, Map<UUID, Person> persons) {
        if (membership == null) {
            return null;
        }
        String fullName = PersonDirectory.of(persons, membership.getUserAccount().getId()).fullName();
        return new PersonRef(membership.getId(),
                fullName == null || fullName.isBlank() ? membership.getUserAccount().getEmail() : fullName);
    }

    static ResourceData toData(Resource r) {
        return new ResourceData(r.getId(), r.getCode(), r.getType(), r.getName(), unit(r), family(r.getFamily()),
                supplier(r.getSupplier()), r.getBrand(), r.isPercentage(), r.isComposite(), r.isHourlyCost(),
                r.isDiscontinued(), money(r.getCurrentCost()), r.getCostUpdatedAt(), r.getSource(),
                r.getMissingFromImportAt(), r.getArchivedAt());
    }

    private static UnitRef unit(Resource r) {
        return new UnitRef(r.getUnit().getId(), r.getUnit().getCode());
    }

    private static FamilyRef family(Family family) {
        return family == null ? null : new FamilyRef(family.getId(), family.getCode(), family.getName());
    }

    private static SupplierRef supplier(Supplier supplier) {
        return supplier == null ? null : new SupplierRef(supplier.getId(), supplier.getName());
    }

    static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Lower-case LIKE pattern for a contains search, or null when the query is blank. */
    static String pattern(String query) {
        String folded = PricingText.fold(query);
        if (folded.isEmpty()) {
            return null;
        }
        return "%" + folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

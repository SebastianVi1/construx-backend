package org.andre.construx.modules.pricing.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.andre.construx.modules.identity.service.PersonDirectory;
import org.andre.construx.modules.identity.service.PersonDirectory.Person;
import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.CostSource;
import org.andre.construx.modules.pricing.model.PricingText;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceCostHistory;
import org.andre.construx.modules.pricing.model.ResourceSource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.ResourceSpecifications;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads of the organization's Resources and their Cost history (docs/pricing.md §2.4, §2.5). */
@Service
public class ResourceService {
    /** Entries shown inside the detail; the full history is the paged costs list. */
    static final int DETAIL_HISTORY = 100;
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "code", "code", "name", "name", "currentCost", "currentCost", "costUpdatedAt", "costUpdatedAt");
    private static final Sort BY_CODE = Sort.by("code");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("effectiveFrom"), Sort.Order.desc("id"));

    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository history;
    private final PersonDirectory people;

    public ResourceService(ResourceRepository resources, ResourceCostHistoryRepository history,
                           PersonDirectory people) {
        this.resources = resources;
        this.history = history;
        this.people = people;
    }

    /** Null fields do not filter; {@code query} is a case-insensitive contains over code, name and description. */
    public record ResourceFilter(List<ResourceType> types, String query, UUID familyId, UUID unitId, UUID supplierId,
                                 Boolean composite, boolean includeArchived, Boolean missingFromImport,
                                 Boolean discontinued) {}

    public record UnitRef(UUID id, String code) {}

    public record FamilyRef(UUID id, String code, String name) {}

    public record SupplierRef(UUID id, String name) {}

    public record PersonRef(UUID membershipId, String fullName) {}

    public record ResourceRef(UUID id, String code, String name, ResourceType type) {}

    /** {@code ResourceResponse}: money is a string with two decimals. */
    public record ResourceData(UUID id, String code, ResourceType type, String name, UnitRef unit, FamilyRef family,
                               SupplierRef supplier, String brand,
                               @JsonProperty("isPercentage") boolean isPercentage,
                               @JsonProperty("isComposite") boolean isComposite,
                               @JsonProperty("isHourlyCost") boolean isHourlyCost,
                               boolean discontinued, String currentCost, Instant costUpdatedAt,
                               ResourceSource source, Instant missingFromImportAt, Instant archivedAt) {}

    /** {@code importId} stays null until the import slice adds the column. */
    public record CostEntry(UUID id, String amount, Instant effectiveFrom, CostSource source, PersonRef recordedBy,
                            UUID importId, SupplierRef supplier, String sku, String note) {}

    /** Parents whose analysis uses the Resource; empty until analysis lines exist. */
    public record UsedIn(long count, List<ResourceRef> sample) {
        static final UsedIn NONE = new UsedIn(0, List.of());
    }

    /**
     * {@code ResourceDetailResponse}: the list row plus the long fields, the last {@value #DETAIL_HISTORY} Cost
     * history entries newest first and where the Resource is used.
     */
    public record ResourceDetail(@JsonUnwrapped ResourceData resource, String description, String reference,
                                 String sapCode, String technicalSheet, String procedure, String grouper,
                                 String importedCost, LocalDate importedCostOn, Instant createdAt, Instant updatedAt,
                                 List<CostEntry> history, UsedIn usedIn) {}

    @Transactional(readOnly = true)
    public PageData<ResourceData> list(OrgScope org, ResourceFilter filter, int page, int size, String sort) {
        org.require(PermissionAction.VIEW_PRICES);
        PageRequest request = PageQuery.of(page, size, sort, SORT_FIELDS, BY_CODE, BY_CODE);
        return PageData.of(resources.findAll(ResourceSpecifications.resources(org.organizationId(), filter.types(),
                pattern(filter.query()), filter.familyId(), filter.unitId(), filter.supplierId(), filter.composite(),
                filter.includeArchived(), filter.missingFromImport(), filter.discontinued()), request),
                ResourceService::toData);
    }

    /** Archived Resources are returned too. */
    @Transactional(readOnly = true)
    public ResourceDetail get(OrgScope org, UUID resourceId) {
        Resource resource = load(org.organizationId(), resourceId);
        org.require(PermissionAction.VIEW_PRICES);
        List<ResourceCostHistory> latest = history.findAll(
                ResourceSpecifications.costs(org.organizationId(), resourceId, null, null),
                PageRequest.of(0, DETAIL_HISTORY, NEWEST_FIRST)).getContent();
        return new ResourceDetail(toData(resource), resource.getDescription(), resource.getReference(),
                resource.getSapCode(), resource.getTechnicalSheet(), resource.getProcedure(), resource.getGrouper(),
                money(resource.getImportedCost()), resource.getImportedCostOn(), resource.getCreatedAt(),
                resource.getUpdatedAt(), toEntries(latest), UsedIn.NONE);
    }

    /** The full Cost history newest first; {@code from} and {@code to} are inclusive and optional. */
    @Transactional(readOnly = true)
    public PageData<CostEntry> costs(OrgScope org, UUID resourceId, Instant from, Instant to, int page, int size) {
        load(org.organizationId(), resourceId);
        org.require(PermissionAction.VIEW_PRICES);
        PageRequest request = PageQuery.of(page, size, null, Map.of(), NEWEST_FIRST, Sort.unsorted());
        Page<ResourceCostHistory> entries = history.findAll(
                ResourceSpecifications.costs(org.organizationId(), resourceId, from, to), request);
        Map<UUID, Person> names = namesOf(entries.getContent());
        return PageData.of(entries, e -> toEntry(e, names));
    }

    /** Resolves a Resource inside the tenant; another organization's id is not found. */
    Resource load(UUID organizationId, UUID resourceId) {
        return resources.findByIdAndOrganizationId(resourceId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Resource not found"));
    }

    private List<CostEntry> toEntries(List<ResourceCostHistory> entries) {
        Map<UUID, Person> names = namesOf(entries);
        return entries.stream().map(e -> toEntry(e, names)).toList();
    }

    /** Names of the people who recorded {@code entries}, read in one query. */
    private Map<UUID, Person> namesOf(List<ResourceCostHistory> entries) {
        return people.byUserId(entries.stream().map(ResourceCostHistory::getRecordedBy)
                .filter(Objects::nonNull).map(m -> m.getUserAccount().getId()).toList());
    }

    private static CostEntry toEntry(ResourceCostHistory e, Map<UUID, Person> names) {
        OrganizationMembership by = e.getRecordedBy();
        PersonRef recordedBy = by == null ? null
                : new PersonRef(by.getId(), PersonDirectory.of(names, by.getUserAccount().getId()).fullName());
        return new CostEntry(e.getId(), money(e.getAmount()), e.getEffectiveFrom(), e.getSource(), recordedBy,
                null, supplierRef(e.getSupplier()), e.getSku(), e.getNote());
    }

    private static SupplierRef supplierRef(Supplier supplier) {
        return supplier == null ? null : new SupplierRef(supplier.getId(), supplier.getName());
    }

    static ResourceData toData(Resource r) {
        FamilyRef family = r.getFamily() == null ? null
                : new FamilyRef(r.getFamily().getId(), r.getFamily().getCode(), r.getFamily().getName());
        return new ResourceData(r.getId(), r.getCode(), r.getType(), r.getName(),
                new UnitRef(r.getUnit().getId(), r.getUnit().getCode()), family, supplierRef(r.getSupplier()),
                r.getBrand(), r.isPercentage(), r.isComposite(), r.isHourlyCost(), r.isDiscontinued(), money(r.getCurrentCost()),
                r.getCostUpdatedAt(), r.getSource(), r.getMissingFromImportAt(), r.getArchivedAt());
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Lower-case LIKE pattern for a contains search, or null when the query is blank. */
    private static String pattern(String query) {
        String folded = PricingText.fold(query);
        if (folded.isEmpty()) {
            return null;
        }
        return "%" + folded.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

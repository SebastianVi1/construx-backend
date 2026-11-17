package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.Family;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.PricingRuleException;
import org.andre.construx.modules.pricing.model.PricingValidationException;
import org.andre.construx.modules.pricing.model.Resource;
import org.andre.construx.modules.pricing.model.ResourceType;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.model.Unit;
import org.andre.construx.modules.pricing.repository.ResourceCostHistoryRepository;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.service.ResourceService.ResourceDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Hand edits of the Catalog: creating simple Materials and Equipment rates, editing, archiving and recording
 * their costs (docs/pricing.md §2.4, §2.5). Labor categories arrive with the labor slice.
 */
@Service
public class ResourceWriteService {
    private final ResourceService reads;
    private final ResourceRepository resources;
    private final ResourceCostHistoryRepository history;
    private final CatalogService catalog;
    private final SupplierService suppliers;

    public ResourceWriteService(ResourceService reads, ResourceRepository resources,
                                ResourceCostHistoryRepository history, CatalogService catalog,
                                SupplierService suppliers) {
        this.reads = reads;
        this.resources = resources;
        this.history = history;
        this.catalog = catalog;
        this.suppliers = suppliers;
    }

    /** {@code isComposite} and {@code hasLaborDetail} only exist to be refused. */
    public record ResourceCreate(ResourceType type, String code, String name, String description, UUID unitId,
                                 UUID familyId, UUID supplierId, String brand, BigDecimal cost, Boolean isComposite,
                                 boolean hasLaborDetail) {}

    /**
     * Null keeps the stored value; {@code ""} clears an optional text, the family or the supplier. Ids travel
     * as text so that {@code ""} can clear them.
     */
    public record ResourcePatch(String name, String description, String unitId, String familyId, String supplierId,
                                String brand, Boolean discontinued, String reference, String sapCode,
                                String technicalSheet, String procedure) {}

    public record CostEntry(BigDecimal amount, UUID supplierId, String sku, String note) {}

    @Transactional
    public ResourceDetail create(OrgScope org, ResourceCreate body) {
        UUID organizationId = org.organizationId();
        Unit unit = body.unitId() == null ? null : unit(organizationId, body.unitId());
        Family family = body.familyId() == null ? null : family(organizationId, body.familyId());
        Supplier supplier = body.supplierId() == null ? null : supplier(organizationId, body.supplierId());
        org.require(PermissionAction.MANAGE_PRICES);
        if (body.type() == null) {
            throw new PricingValidationException("Type is required");
        }
        if (body.type() == ResourceType.WORK_ITEM || Boolean.TRUE.equals(body.isComposite())) {
            throw new PricingValidationException("Composite resources and work items cannot be created by hand");
        }
        if (body.type() == ResourceType.LABOR) {
            throw new PricingValidationException("Labor categories cannot be created by hand yet");
        }
        if (body.hasLaborDetail()) {
            throw new PricingValidationException("Only labor categories have a labor detail");
        }
        Instant now = now();
        Resource resource = Resource.manual(organizationId, body.type(), body.code(), body.name(),
                body.description(), unit, family, supplier, body.brand(), now);
        BigDecimal cost = Resource.checkCost(body.cost(), "cost");
        if (resources.existsByOrganizationIdAndCode(organizationId, resource.getCode())) {
            throw new PricingConflictException("DUPLICATE_CODE", "A resource with this code already exists");
        }
        catalog.ensureCatalog(organizationId);
        // Ids are assigned, so save() merges a copy: the first cost goes on before the Resource is saved.
        var first = resource.recordCost(cost, now, org.membership().getId(), null, null, null);
        resources.save(resource);
        history.save(first);
        resources.flush();
        return reads.get(org, resource.getId());
    }

    @Transactional
    public ResourceDetail update(OrgScope org, UUID resourceId, ResourcePatch patch) {
        UUID organizationId = org.organizationId();
        Resource r = reads.load(organizationId, resourceId);
        Unit unit = patch.unitId() == null ? r.getUnit() : unit(organizationId, id(patch.unitId(), "unitId"));
        Family family = patch.familyId() == null ? r.getFamily()
                : patch.familyId().isBlank() ? null : family(organizationId, id(patch.familyId(), "familyId"));
        Supplier supplier = patch.supplierId() == null ? r.getSupplier()
                : patch.supplierId().isBlank() ? null : supplier(organizationId, id(patch.supplierId(), "supplierId"));
        org.require(PermissionAction.MANAGE_PRICES);
        r.describe(keep(patch.name(), r.getName()), keep(patch.description(), r.getDescription()), unit, family,
                supplier, keep(patch.brand(), r.getBrand()),
                patch.discontinued() != null ? patch.discontinued() : r.isDiscontinued(),
                keep(patch.reference(), r.getReference()), keep(patch.sapCode(), r.getSapCode()),
                keep(patch.technicalSheet(), r.getTechnicalSheet()), keep(patch.procedure(), r.getProcedure()),
                now());
        resources.flush();
        return reads.get(org, resourceId);
    }

    /** Archived Resources keep their history and stay readable; archiving twice changes nothing. */
    @Transactional
    public ResourceDetail archive(OrgScope org, UUID resourceId) {
        Resource r = reads.load(org.organizationId(), resourceId);
        org.require(PermissionAction.MANAGE_PRICES);
        r.archive(now());
        resources.flush();
        return reads.get(org, resourceId);
    }

    @Transactional
    public ResourceDetail recordCost(OrgScope org, UUID resourceId, CostEntry entry) {
        UUID organizationId = org.organizationId();
        Resource r = reads.load(organizationId, resourceId);
        Supplier supplier = entry.supplierId() == null ? null : supplier(organizationId, entry.supplierId());
        org.require(PermissionAction.MANAGE_PRICES);
        history.save(r.recordCost(entry.amount(), now(), org.membership().getId(), supplier, entry.sku(),
                entry.note()));
        resources.flush();
        return reads.get(org, resourceId);
    }

    private Unit unit(UUID organizationId, UUID id) {
        return catalog.loadUnit(organizationId, id);
    }

    private Family family(UUID organizationId, UUID id) {
        return catalog.loadFamily(organizationId, id);
    }

    private Supplier supplier(UUID organizationId, UUID id) {
        return suppliers.load(organizationId, id);
    }

    private static UUID id(String value, String field) {
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new PricingRuleException(field, "INVALID", field + " must be an id");
        }
    }

    /** Microseconds, as the database stores them, so a write answers the same instants a later read does. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Null keeps the stored text; anything else, {@code ""} included, replaces it. */
    private static String keep(String value, String stored) {
        return value != null ? value : stored;
    }
}

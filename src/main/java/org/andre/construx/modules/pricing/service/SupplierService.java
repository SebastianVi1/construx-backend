package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.CatalogSource;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.ResourceRepository;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores and branches an organization records prices from (docs/pricing.md §1.4, §2.3). */
@Service
public class SupplierService {
    private final SupplierRepository suppliers;
    private final ResourceRepository resources;
    private final CatalogService catalog;

    public SupplierService(SupplierRepository suppliers, ResourceRepository resources, CatalogService catalog) {
        this.suppliers = suppliers;
        this.resources = resources;
        this.catalog = catalog;
    }

    public record SupplierData(UUID id, String name, String branch, String website, String code, String rfc,
                               String address, String neighborhood, String city, String state, String postalCode,
                               String phone, String mobile, String fax, String email, String contact, String notes,
                               boolean isDefault, CatalogSource source, Instant archivedAt, long resourceCount) {}

    /**
     * Writable supplier fields. On create, null means absent; on update, null keeps the stored value and an
     * empty string clears an optional one.
     */
    public record SupplierInput(String name, String branch, String website, String code, String rfc, String address,
                                String neighborhood, String city, String state, String postalCode, String phone,
                                String mobile, String fax, String email, String contact, String notes,
                                Boolean isDefault) {}

    @Transactional
    public SupplierData create(OrgScope org, SupplierInput input) {
        OrganizationMembership member = org.membership();
        org.require(PermissionAction.MANAGE_PRICES);
        Supplier supplier = new Supplier(member.getOrganization(), input.name(), input.branch(), input.website());
        supplier.recode(input.code());
        supplier.detail(merge(supplier.details(), input));
        rejectDuplicate(org.organizationId(), null, supplier.getNormalizedKey());
        rejectDuplicateCode(org.organizationId(), null, supplier.getCode());
        catalog.ensureCatalog(org.organizationId());
        return toData(suppliers.saveAndFlush(supplier), 0);
    }

    /** Blank or absent {@code query} lists everything; otherwise contains over name, code and contact. */
    @Transactional(readOnly = true)
    public List<SupplierData> list(OrgScope org, boolean includeArchived, String query) {
        org.require(PermissionAction.VIEW_PRICES);
        Map<UUID, Long> used = new HashMap<>();
        for (Object[] row : resources.countBySupplier(org.organizationId())) {
            used.put((UUID) row[0], (Long) row[1]);
        }
        return suppliers.list(org.organizationId(), includeArchived, CatalogSearch.containsPattern(query)).stream()
                .map(s -> toData(s, used.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    /** Null fields keep the stored value; an empty string clears an optional one. */
    @Transactional
    public SupplierData update(OrgScope org, UUID supplierId, SupplierInput input) {
        Supplier supplier = load(org.organizationId(), supplierId);
        org.require(PermissionAction.MANAGE_PRICES);
        String newName = input.name() != null ? input.name() : supplier.getName();
        String newBranch = input.branch() != null ? input.branch() : supplier.getBranch();
        String newWebsite = input.website() != null ? input.website() : supplier.getWebsite();
        rejectDuplicate(org.organizationId(), supplierId, Supplier.keyOf(newName, newBranch));
        if (input.code() != null) {
            rejectDuplicateCode(org.organizationId(), supplierId, Supplier.codeOf(input.code()));
            supplier.recode(input.code());
        }
        supplier.describe(newName, newBranch, newWebsite);
        supplier.detail(merge(supplier.details(), input));
        return toData(suppliers.saveAndFlush(supplier), resourceCount(org.organizationId(), supplierId));
    }

    /** Suppliers are never deleted, so their price history stays readable. */
    @Transactional
    public SupplierData archive(OrgScope org, UUID supplierId) {
        Supplier supplier = load(org.organizationId(), supplierId);
        org.require(PermissionAction.MANAGE_PRICES);
        supplier.archive(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return toData(supplier, resourceCount(org.organizationId(), supplierId));
    }

    /** Resolves a supplier inside the tenant. */
    public Supplier load(UUID organizationId, UUID supplierId) {
        return suppliers.findByIdAndOrganizationId(supplierId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
    }

    private long resourceCount(UUID organizationId, UUID supplierId) {
        return resources.countByOrganizationIdAndSupplierId(organizationId, supplierId);
    }

    private static Supplier.Details merge(Supplier.Details current, SupplierInput input) {
        return new Supplier.Details(
                keep(input.rfc(), current.rfc()), keep(input.address(), current.address()),
                keep(input.neighborhood(), current.neighborhood()), keep(input.city(), current.city()),
                keep(input.state(), current.state()), keep(input.postalCode(), current.postalCode()),
                keep(input.phone(), current.phone()), keep(input.mobile(), current.mobile()),
                keep(input.fax(), current.fax()), keep(input.email(), current.email()),
                keep(input.contact(), current.contact()), keep(input.notes(), current.notes()),
                input.isDefault() != null ? input.isDefault() : current.isDefault());
    }

    private static String keep(String sent, String stored) {
        return sent != null ? sent : stored;
    }

    private void rejectDuplicate(UUID organizationId, UUID selfId, String key) {
        suppliers.findByOrganizationIdAndNormalizedKey(organizationId, key)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new PricingConflictException("DUPLICATE_SUPPLIER",
                            "A supplier with this name and branch already exists");
                });
    }

    private void rejectDuplicateCode(UUID organizationId, UUID selfId, String code) {
        if (code == null) {
            return;
        }
        suppliers.findByOrganizationIdAndCode(organizationId, code)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new PricingConflictException("DUPLICATE_SUPPLIER", "A supplier with this code already exists");
                });
    }

    static SupplierData toData(Supplier s, long resourceCount) {
        return new SupplierData(s.getId(), s.getName(), s.getBranch(), s.getWebsite(), s.getCode(), s.getRfc(),
                s.getAddress(), s.getNeighborhood(), s.getCity(), s.getState(), s.getPostalCode(), s.getPhone(),
                s.getMobile(), s.getFax(), s.getEmail(), s.getContact(), s.getNotes(), s.isDefault(), s.getSource(),
                s.getArchivedAt(), resourceCount);
    }
}

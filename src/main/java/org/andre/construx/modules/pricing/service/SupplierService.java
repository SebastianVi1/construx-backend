package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stores and branches an organization records prices from. */
@Service
public class SupplierService {
    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    public record SupplierData(UUID id, String name, String branch, String website, Instant archivedAt) {}

    @Transactional
    public SupplierData create(OrgScope org, String name, String branch, String website) {
        OrganizationMembership member = org.membership();
        org.require(PermissionAction.MANAGE_PRICES);
        Supplier supplier = new Supplier(member.getOrganization(), name, branch, website);
        rejectDuplicate(org.organizationId(), null, supplier.getNormalizedKey());
        return toData(suppliers.saveAndFlush(supplier));
    }

    @Transactional(readOnly = true)
    public List<SupplierData> list(OrgScope org, boolean includeArchived) {
        org.require(PermissionAction.VIEW_PRICES);
        return suppliers.list(org.organizationId(), includeArchived).stream().map(SupplierService::toData).toList();
    }

    /** Null arguments keep the stored value; an empty branch or website clears it. */
    @Transactional
    public SupplierData update(OrgScope org, UUID supplierId, String name, String branch,
                               String website) {
        Supplier supplier = load(org.organizationId(), supplierId);
        org.require(PermissionAction.MANAGE_PRICES);
        String newName = name != null ? name : supplier.getName();
        String newBranch = branch != null ? branch : supplier.getBranch();
        String newWebsite = website != null ? website : supplier.getWebsite();
        rejectDuplicate(org.organizationId(), supplierId, Supplier.keyOf(newName, newBranch));
        supplier.describe(newName, newBranch, newWebsite);
        return toData(suppliers.saveAndFlush(supplier));
    }

    /** Suppliers are never deleted, so their price history stays readable. */
    @Transactional
    public SupplierData archive(OrgScope org, UUID supplierId) {
        Supplier supplier = load(org.organizationId(), supplierId);
        org.require(PermissionAction.MANAGE_PRICES);
        supplier.archive(Instant.now());
        return toData(supplier);
    }

    /** Resolves a supplier inside the tenant. */
    public Supplier load(UUID organizationId, UUID supplierId) {
        return suppliers.findByIdAndOrganizationId(supplierId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
    }

    private void rejectDuplicate(UUID organizationId, UUID selfId, String key) {
        suppliers.findByOrganizationIdAndNormalizedKey(organizationId, key)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new PricingConflictException("DUPLICATE_SUPPLIER",
                            "A supplier with this name and branch already exists");
                });
    }

    static SupplierData toData(Supplier supplier) {
        return new SupplierData(supplier.getId(), supplier.getName(), supplier.getBranch(), supplier.getWebsite(),
                supplier.getArchivedAt());
    }
}

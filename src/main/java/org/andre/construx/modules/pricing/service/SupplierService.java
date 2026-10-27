package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.model.OrganizationMembership;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.pricing.model.PricingConflictException;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.SupplierRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stores and branches an organization records prices from. */
@Service
public class SupplierService {
    private final SupplierRepository suppliers;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public SupplierService(SupplierRepository suppliers, MembershipGuard guard, DomainAuthorization authorization) {
        this.suppliers = suppliers;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record SupplierData(UUID id, String name, String branch, String website, Instant archivedAt) {}

    @Transactional
    public SupplierData create(UUID userId, UUID organizationId, String name, String branch, String website) {
        OrganizationMembership member = guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        Supplier supplier = new Supplier(member.getOrganization(), name, branch, website);
        rejectDuplicate(organizationId, null, supplier.getNormalizedKey());
        return toData(suppliers.saveAndFlush(supplier));
    }

    @Transactional(readOnly = true)
    public List<SupplierData> list(UUID userId, UUID organizationId, boolean includeArchived) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_PRICES);
        return suppliers.list(organizationId, includeArchived).stream().map(SupplierService::toData).toList();
    }

    /** Null arguments keep the stored value; an empty branch or website clears it. */
    @Transactional
    public SupplierData update(UUID userId, UUID organizationId, UUID supplierId, String name, String branch,
                               String website) {
        guard.requireActive(userId, organizationId);
        Supplier supplier = load(organizationId, supplierId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        String newName = name != null ? name : supplier.getName();
        String newBranch = branch != null ? branch : supplier.getBranch();
        String newWebsite = website != null ? website : supplier.getWebsite();
        rejectDuplicate(organizationId, supplierId, Supplier.keyOf(newName, newBranch));
        supplier.describe(newName, newBranch, newWebsite);
        return toData(suppliers.saveAndFlush(supplier));
    }

    /** Suppliers are never deleted, so their price history stays readable. */
    @Transactional
    public SupplierData archive(UUID userId, UUID organizationId, UUID supplierId) {
        guard.requireActive(userId, organizationId);
        Supplier supplier = load(organizationId, supplierId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
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

package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.modules.pricing.model.Material;
import org.andre.construx.modules.pricing.model.PriceObservation;
import org.andre.construx.modules.pricing.model.PriceSource;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.PriceObservationRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Records and deletes price observations; there is no update, observations are immutable. */
@Service
public class PriceObservationService {
    private final PriceObservationRepository observations;
    private final MaterialService materials;
    private final SupplierService suppliers;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public PriceObservationService(PriceObservationRepository observations, MaterialService materials,
                                   SupplierService suppliers, MembershipGuard guard,
                                   DomainAuthorization authorization) {
        this.observations = observations;
        this.materials = materials;
        this.suppliers = suppliers;
        this.guard = guard;
        this.authorization = authorization;
    }

    /** {@code price} is a decimal string with two decimals, MXN including IVA. */
    public record PriceData(UUID id, UUID materialId, UUID supplierId, String supplierName, String supplierBranch,
                            String price, LocalDate observedOn, String sku, PriceSource source, Instant createdAt) {}

    public record LatestPrice(UUID id, UUID materialId, UUID supplierId, String supplierName, String supplierBranch,
                              String price, LocalDate observedOn, String sku, PriceSource source, Instant createdAt,
                              boolean cheapest) {}

    @Transactional
    public PriceData record(UUID userId, UUID organizationId, UUID materialId, UUID supplierId, BigDecimal price,
                            LocalDate observedOn, String sku) {
        guard.requireActive(userId, organizationId);
        Material material = materials.load(organizationId, materialId);
        Supplier supplier = suppliers.load(organizationId, supplierId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        PriceObservation observation = new PriceObservation(material, supplier, price, observedOn, sku,
                PriceSource.MANUAL, today(), Instant.now());
        return toData(observations.save(observation));
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID observationId) {
        guard.requireActive(userId, organizationId);
        PriceObservation observation = observations.findByIdAndOrganizationId(observationId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Price observation not found"));
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PRICES);
        observations.delete(observation);
    }

    /**
     * Latest allowed observation day. UTC is ahead of every Mexican time zone, so a price seen
     * today anywhere in Mexico is never rejected.
     */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    static PriceData toData(PriceObservation o) {
        Supplier supplier = o.getSupplier();
        return new PriceData(o.getId(), o.getMaterial().getId(), supplier.getId(), supplier.getName(),
                supplier.getBranch(), o.getPrice().toPlainString(), o.getObservedOn(), o.getSku(), o.getSource(),
                o.getCreatedAt());
    }

    static LatestPrice toLatest(PriceObservation o, boolean cheapest) {
        PriceData d = toData(o);
        return new LatestPrice(d.id(), d.materialId(), d.supplierId(), d.supplierName(), d.supplierBranch(),
                d.price(), d.observedOn(), d.sku(), d.source(), d.createdAt(), cheapest);
    }
}

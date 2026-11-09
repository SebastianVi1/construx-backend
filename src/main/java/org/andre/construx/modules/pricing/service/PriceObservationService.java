package org.andre.construx.modules.pricing.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.pricing.model.Material;
import org.andre.construx.modules.pricing.model.PriceObservation;
import org.andre.construx.modules.pricing.model.PriceSource;
import org.andre.construx.modules.pricing.model.Supplier;
import org.andre.construx.modules.pricing.repository.PriceObservationRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
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

    public PriceObservationService(PriceObservationRepository observations, MaterialService materials,
                                   SupplierService suppliers) {
        this.observations = observations;
        this.materials = materials;
        this.suppliers = suppliers;
    }

    /** {@code price} is a decimal string with two decimals, MXN including IVA. */
    public record PriceData(UUID id, UUID materialId, UUID supplierId, String supplierName, String supplierBranch,
                            String price, LocalDate observedOn, String sku, PriceSource source, Instant createdAt) {}

    public record LatestPrice(UUID id, UUID materialId, UUID supplierId, String supplierName, String supplierBranch,
                              String price, LocalDate observedOn, String sku, PriceSource source, Instant createdAt,
                              boolean cheapest) {}

    @Transactional
    public PriceData record(OrgScope org, UUID materialId, UUID supplierId, BigDecimal price,
                            LocalDate observedOn, String sku) {
        Material material = materials.load(org.organizationId(), materialId);
        Supplier supplier = suppliers.load(org.organizationId(), supplierId);
        org.require(PermissionAction.MANAGE_PRICES);
        PriceObservation observation = new PriceObservation(material, supplier, price, observedOn, sku,
                PriceSource.MANUAL, today(), Instant.now());
        return toData(observations.save(observation));
    }

    @Transactional
    public void delete(OrgScope org, UUID observationId) {
        PriceObservation observation = observations.findByIdAndOrganizationId(observationId, org.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Price observation not found"));
        org.require(PermissionAction.MANAGE_PRICES);
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

package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Standby and reserve shares of one equipment charge, catalog-wide ({@code PorcentajesCh}); read-only in v1. */
@Entity
@Table(name = "equipment_standby_percentage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentStandbyPercentage {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 40)
    private String charge;

    @Column(nullable = false, precision = 8, scale = 6)
    private BigDecimal standbyPct;

    @Column(nullable = false, precision = 8, scale = 6)
    private BigDecimal reservePct;
}

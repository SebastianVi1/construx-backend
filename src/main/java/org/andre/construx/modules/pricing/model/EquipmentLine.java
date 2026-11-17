package org.andre.construx.modules.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One operation ({@code OperacionCH}) or consumption ({@code ConsumosCH}) line of an Equipment rate
 * (docs/pricing.md §1.9); display only.
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EquipmentLine {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "equipment_resource_id", nullable = false)
    private UUID equipmentResourceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_resource_id", nullable = false)
    private Resource component;

    @Column(nullable = false, length = 1)
    private Character operator;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Entity(name = "EquipmentOperationLine")
    @Table(name = "equipment_operation_line")
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Operation extends EquipmentLine {}

    @Entity(name = "EquipmentConsumptionLine")
    @Table(name = "equipment_consumption_line")
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Consumption extends EquipmentLine {}
}

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

/**
 * The machinery data behind an Equipment rate as the file has it (docs/pricing.md §1.9). Stored for display:
 * the hourly cost is the Resource's own cost and nothing is recomputed from these values in v1.
 */
@Entity
@Table(name = "equipment_rate_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentRateDetail {
    @Id
    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(length = 10)
    private String powerUnit;

    @Column(length = 40)
    private String fixedChargeCode;

    @Column(length = 40)
    private String acquisitionValueCode;

    @Column(length = 40)
    private String standbyCode;

    @Column(length = 40)
    private String reserveCode;

    @Column(length = 40)
    private String tiresCode;

    @Column(length = 40)
    private String specialPartsCode;

    @Column(length = 40)
    private String fuelCode;

    @Column(length = 40)
    private String lubricantCode;

    @Column(length = 40)
    private String otherEnergyCode;

    @Column(precision = 12, scale = 2)
    private BigDecimal tiresLifeHours;

    @Column(precision = 12, scale = 2)
    private BigDecimal specialPartsLifeHours;

    @Column(precision = 12, scale = 2)
    private BigDecimal economicLifeHours;

    @Column(precision = 12, scale = 2)
    private BigDecimal hoursPerYear;

    @Column(precision = 12, scale = 2)
    private BigDecimal oilChangeHours;

    private Short calculationType;

    @Column(precision = 12, scale = 4)
    private BigDecimal power;

    @Column(precision = 10, scale = 6)
    private BigDecimal operationFactor;

    private Short fuelType;

    @Column(precision = 12, scale = 8)
    private BigDecimal fuelFactor;

    @Column(precision = 12, scale = 8)
    private BigDecimal otherEnergyFactor;

    @Column(precision = 12, scale = 8)
    private BigDecimal lubricantFactor;

    @Column(precision = 12, scale = 4)
    private BigDecimal crankcaseCapacity;

    @Column(precision = 10, scale = 6)
    private BigDecimal salvagePct;

    @Column(precision = 10, scale = 6)
    private BigDecimal insurancePct;

    @Column(precision = 10, scale = 6)
    private BigDecimal maintenanceFactor;

    @Column(precision = 10, scale = 6)
    private BigDecimal interestRate;

    @Column(length = 120)
    private String brand;

    @Column(length = 120)
    private String model;

    @Column(length = 120)
    private String serialNumber;

    @Column(length = 120)
    private String capacity;

    @Column(length = 120)
    private String location;

    @Column(length = 120)
    private String usefulLife;

    @Column(length = 120)
    private String owner;

    @Column(length = 120)
    private String availability;

    @Column(precision = 14, scale = 2)
    private BigDecimal commercialValue;

    @Column(precision = 12, scale = 6)
    private BigDecimal fuelQtyPerHour;

    @Column(precision = 12, scale = 6)
    private BigDecimal lubricantQtyPerHour;
}

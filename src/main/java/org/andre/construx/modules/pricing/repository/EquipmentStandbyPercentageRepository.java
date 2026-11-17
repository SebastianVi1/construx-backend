package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.EquipmentStandbyPercentage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EquipmentStandbyPercentageRepository extends JpaRepository<EquipmentStandbyPercentage, UUID> {
    List<EquipmentStandbyPercentage> findByOrganizationIdOrderByCharge(UUID organizationId);
}

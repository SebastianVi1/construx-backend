package org.andre.construx.modules.crew.repository;

import org.andre.construx.modules.crew.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {

    List<Equipment> findByOrganizationIdOrderByTypeAscIdentifierAsc(UUID organizationId);

    List<Equipment> findByOrganizationIdAndActiveOrderByTypeAscIdentifierAsc(UUID organizationId, boolean active);

    Optional<Equipment> findByIdAndOrganizationId(UUID id, UUID organizationId);
}

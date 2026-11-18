package org.andre.construx.modules.pricing.repository;

import org.andre.construx.modules.pricing.model.EquipmentLine;
import org.andre.construx.modules.pricing.model.EquipmentRateDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentRateDetailRepository extends JpaRepository<EquipmentRateDetail, UUID> {
    Optional<EquipmentRateDetail> findByResourceIdAndOrganizationId(UUID resourceId, UUID organizationId);

    @Query("""
            select l from EquipmentOperationLine l join fetch l.component c join fetch c.unit
            where l.organizationId = :organizationId and l.equipmentResourceId = :equipmentId
            order by l.lineNo
            """)
    List<EquipmentLine.Operation> operationLines(@Param("organizationId") UUID organizationId,
                                                 @Param("equipmentId") UUID equipmentId);

    @Query("""
            select l from EquipmentConsumptionLine l join fetch l.component c join fetch c.unit
            where l.organizationId = :organizationId and l.equipmentResourceId = :equipmentId
            order by l.lineNo
            """)
    List<EquipmentLine.Consumption> consumptionLines(@Param("organizationId") UUID organizationId,
                                                     @Param("equipmentId") UUID equipmentId);
}

package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepo;

    public EquipmentService(EquipmentRepository equipmentRepo) {
        this.equipmentRepo = equipmentRepo;
    }

    public record EquipmentData(UUID id, UUID organizationId, String type, String identifier,
                                Long dailyCostCents, boolean active, Instant createdAt) {}

    @Transactional
    public EquipmentData createEquipment(OrgScope org, String type,
                                         String identifier, Long dailyCostCents) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = equipmentRepo.save(new Equipment(org.membership().getOrganization(), type, identifier, dailyCostCents));
        return toData(equipment);
    }

    @Transactional
    public EquipmentData updateEquipment(OrgScope org, UUID equipmentId,
                                          String type, String identifier, Long dailyCostCents, Boolean active) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        equipment.update(type, identifier, dailyCostCents, active);
        return toData(equipment);
    }

    @Transactional
    public EquipmentData deactivateEquipment(OrgScope org, UUID equipmentId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        equipment.deactivate();
        return toData(equipment);
    }

    @Transactional(readOnly = true)
    public List<EquipmentData> listEquipment(OrgScope org, Boolean activeOnly) {
        org.require(PermissionAction.VIEW_PLAN);
        List<Equipment> result = (activeOnly != null && activeOnly)
                ? equipmentRepo.findByOrganizationIdAndActiveOrderByTypeAscIdentifierAsc(org.organizationId(), true)
                : equipmentRepo.findByOrganizationIdOrderByTypeAscIdentifierAsc(org.organizationId());
        return result.stream().map(this::toData).toList();
    }

    @Transactional
    public void deleteEquipment(OrgScope org, UUID equipmentId) {
        org.require(PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, org.organizationId());
        equipmentRepo.delete(equipment);
    }

    private Equipment loadEquipment(UUID equipmentId, UUID organizationId) {
        return equipmentRepo.findByIdAndOrganizationId(equipmentId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipment not found"));
    }

    private EquipmentData toData(Equipment e) {
        return new EquipmentData(e.getId(), e.getOrganization().getId(), e.getType(),
                e.getIdentifier(), e.getDailyCostCents(), e.isActive(), e.getCreatedAt());
    }
}

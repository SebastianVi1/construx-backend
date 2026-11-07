package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.modules.organization.model.Organization;
import org.andre.construx.modules.organization.model.PermissionAction;
import org.andre.construx.modules.organization.repository.OrganizationRepository;
import org.andre.construx.modules.organization.service.MembershipGuard;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.andre.construx.shared.security.DomainAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepo;
    private final OrganizationRepository organizations;
    private final MembershipGuard guard;
    private final DomainAuthorization authorization;

    public EquipmentService(EquipmentRepository equipmentRepo, OrganizationRepository organizations,
                            MembershipGuard guard, DomainAuthorization authorization) {
        this.equipmentRepo = equipmentRepo;
        this.organizations = organizations;
        this.guard = guard;
        this.authorization = authorization;
    }

    public record EquipmentData(UUID id, UUID organizationId, String type, String identifier,
                                Long dailyCostCents, boolean active, Instant createdAt) {}

    @Transactional
    public EquipmentData createEquipment(UUID userId, UUID organizationId, String type,
                                         String identifier, Long dailyCostCents) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        Equipment equipment = equipmentRepo.save(new Equipment(org, type, identifier, dailyCostCents));
        return toData(equipment);
    }

    @Transactional
    public EquipmentData updateEquipment(UUID userId, UUID organizationId, UUID equipmentId,
                                          String type, String identifier, Long dailyCostCents, Boolean active) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, organizationId);
        equipment.update(type, identifier, dailyCostCents, active);
        return toData(equipment);
    }

    @Transactional
    public EquipmentData deactivateEquipment(UUID userId, UUID organizationId, UUID equipmentId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, organizationId);
        equipment.deactivate();
        return toData(equipment);
    }

    @Transactional(readOnly = true)
    public List<EquipmentData> listEquipment(UUID userId, UUID organizationId, Boolean activeOnly) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.VIEW_PLAN);
        List<Equipment> result = (activeOnly != null && activeOnly)
                ? equipmentRepo.findByOrganizationIdAndActiveOrderByTypeAscIdentifierAsc(organizationId, true)
                : equipmentRepo.findByOrganizationIdOrderByTypeAscIdentifierAsc(organizationId);
        return result.stream().map(this::toData).toList();
    }

    @Transactional
    public void deleteEquipment(UUID userId, UUID organizationId, UUID equipmentId) {
        guard.requireActive(userId, organizationId);
        authorization.require(userId, organizationId, null, null, PermissionAction.MANAGE_PLAN);
        Equipment equipment = loadEquipment(equipmentId, organizationId);
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

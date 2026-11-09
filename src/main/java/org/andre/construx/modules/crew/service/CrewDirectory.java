package org.andre.construx.modules.crew.service;

import org.andre.construx.modules.crew.model.Crew;
import org.andre.construx.modules.crew.model.Equipment;
import org.andre.construx.modules.crew.repository.CrewRepository;
import org.andre.construx.modules.crew.repository.EquipmentRepository;
import org.andre.construx.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read access to a company's crews and equipment for other modules (floor plans). No permission checks: the
 * caller has already authorized the request in its own terms.
 */
@Service
@Transactional(readOnly = true)
public class CrewDirectory {
    private final CrewRepository crews;
    private final EquipmentRepository equipment;

    public CrewDirectory(CrewRepository crews, EquipmentRepository equipment) {
        this.crews = crews;
        this.equipment = equipment;
    }

    /** A crew as other modules list it. */
    public record CrewSummary(UUID id, String name, int memberCount) {}

    /** A piece of equipment as other modules list it. */
    public record EquipmentSummary(UUID id, String type, String identifier) {}

    /**
     * 404 unless the crew belongs to the organization. Returns the entity only because zone assignments still hold a
     * JPA association to it; read crews through {@link #crews} instead.
     */
    public Crew crew(UUID organizationId, UUID crewId) {
        return crews.findByIdAndOrganizationId(crewId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Crew not found"));
    }

    /** 404 unless the equipment belongs to the organization. Entity for the same reason as {@link #crew}. */
    public Equipment equipment(UUID organizationId, UUID equipmentId) {
        return equipment.findByIdAndOrganizationId(equipmentId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Equipment not found"));
    }

    /** Every crew of the organization, by name. */
    public List<CrewSummary> crews(UUID organizationId) {
        return crews.findByOrganizationId(organizationId).stream()
                .map(c -> new CrewSummary(c.getId(), c.getName(), c.getMembers().size()))
                .toList();
    }

    /** Equipment still in service, by type and identifier. */
    public List<EquipmentSummary> activeEquipment(UUID organizationId) {
        return equipment.findByOrganizationIdAndActiveOrderByTypeAscIdentifierAsc(organizationId, true).stream()
                .map(e -> new EquipmentSummary(e.getId(), e.getType(), e.getIdentifier()))
                .toList();
    }
}

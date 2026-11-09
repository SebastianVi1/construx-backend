package org.andre.construx.modules.floorplan.service;

import org.andre.construx.modules.project.service.ActivityService.OpenTask;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * What an assignment puts in a zone, already named, so the plan never looks people up itself.
 *
 * @param id        membership id for a PERSON, crew id for a CREW, equipment id for EQUIPMENT
 * @param label     person name (null without a profile), crew name, or "type — identifier"
 * @param avatarUrl a PERSON's photo link, else null
 * @param members   the people it stands for: the person, every crew member (leader first), none for equipment
 * @param equipment type and identifier for EQUIPMENT, else null
 */
public record Occupant(Kind kind, UUID id, String label, URI avatarUrl, List<Worker> members, EquipmentInfo equipment) {

    public enum Kind { PERSON, CREW, EQUIPMENT }

    /**
     * A person on the plan. {@code openTasks}: their unfinished tasks on this Site, only when the viewer may see
     * them (VIEW_ACTIVITY here, or OWN scope for the viewer's own tasks); otherwise empty.
     */
    public record Worker(UUID membershipId, String fullName, URI avatarUrl, String roleName, boolean crewLeader,
                         List<OpenTask> openTasks) {}

    public record EquipmentInfo(String type, String identifier) {}
}

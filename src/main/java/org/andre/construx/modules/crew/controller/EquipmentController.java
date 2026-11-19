package org.andre.construx.modules.crew.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.crew.service.EquipmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/equipment")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    public record CreateEquipmentRequest(@NotBlank @Size(max = 80) String type,
                                         @NotBlank @Size(max = 80) String identifier,
                                         @Min(0) Long dailyCostCents) {}

    public record UpdateEquipmentRequest(@Size(max = 80) String type,
                                         @Size(max = 80) String identifier,
                                         @Min(0) Long dailyCostCents,
                                         Boolean active) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentService.EquipmentData create(OrgScope org,
                                                  @Valid @RequestBody CreateEquipmentRequest body) {
        return equipmentService.createEquipment(org,
                body.type(), body.identifier(), body.dailyCostCents());
    }

    @GetMapping
    public List<EquipmentService.EquipmentData> list(OrgScope org,
                                                      @RequestParam(required = false) Boolean activeOnly) {
        return equipmentService.listEquipment(org, activeOnly);
    }

    @PatchMapping("/{equipmentId}")
    public EquipmentService.EquipmentData update(OrgScope org,
                                                  @PathVariable UUID equipmentId,
                                                  @Valid @RequestBody UpdateEquipmentRequest body) {
        return equipmentService.updateEquipment(org, equipmentId,
                body.type(), body.identifier(), body.dailyCostCents(), body.active());
    }

    @DeleteMapping("/{equipmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(OrgScope org,
                       @PathVariable UUID equipmentId) {
        equipmentService.deleteEquipment(org, equipmentId);
    }

}

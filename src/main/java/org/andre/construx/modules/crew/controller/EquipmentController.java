package org.andre.construx.modules.crew.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.crew.service.EquipmentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
    public EquipmentService.EquipmentData create(@PathVariable UUID organizationId,
                                                  @Valid @RequestBody CreateEquipmentRequest body,
                                                  Authentication authentication) {
        return equipmentService.createEquipment(userId(authentication), organizationId,
                body.type(), body.identifier(), body.dailyCostCents());
    }

    @GetMapping
    public List<EquipmentService.EquipmentData> list(@PathVariable UUID organizationId,
                                                      @RequestParam(required = false) Boolean activeOnly,
                                                      Authentication authentication) {
        return equipmentService.listEquipment(userId(authentication), organizationId, activeOnly);
    }

    @PatchMapping("/{equipmentId}")
    public EquipmentService.EquipmentData update(@PathVariable UUID organizationId,
                                                  @PathVariable UUID equipmentId,
                                                  @Valid @RequestBody UpdateEquipmentRequest body,
                                                  Authentication authentication) {
        return equipmentService.updateEquipment(userId(authentication), organizationId, equipmentId,
                body.type(), body.identifier(), body.dailyCostCents(), body.active());
    }

    @DeleteMapping("/{equipmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID organizationId,
                       @PathVariable UUID equipmentId,
                       Authentication authentication) {
        equipmentService.deleteEquipment(userId(authentication), organizationId, equipmentId);
    }

    private UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

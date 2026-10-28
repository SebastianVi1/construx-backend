package org.andre.construx.modules.pricing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.pricing.model.MaterialCategory;
import org.andre.construx.modules.pricing.model.MaterialUnit;
import org.andre.construx.modules.pricing.service.MaterialService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/materials")
public class MaterialController {
    private final MaterialService materials;

    public MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    public record MaterialCreate(@NotBlank @Size(max = 160) String name, @NotNull MaterialUnit unit,
                                 @NotNull MaterialCategory category, @Size(max = 80) String brand) {}

    /** Fields left out keep their value; {@code "brand": ""} clears the brand. */
    public record MaterialPatch(@Size(max = 160) String name, MaterialUnit unit, MaterialCategory category,
                                @Size(max = 80) String brand) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialService.MaterialData create(@PathVariable UUID organizationId,
                                               @Valid @RequestBody MaterialCreate body,
                                               Authentication authentication) {
        return materials.create(userId(authentication), organizationId, body.name(), body.unit(), body.category(),
                body.brand());
    }

    @GetMapping
    public List<MaterialService.MaterialData> list(@PathVariable UUID organizationId,
                                                   @RequestParam(required = false) String query,
                                                   @RequestParam(required = false) MaterialCategory category,
                                                   @RequestParam(defaultValue = "false") boolean includeArchived,
                                                   Authentication authentication) {
        return materials.list(userId(authentication), organizationId, query, category, includeArchived);
    }

    @GetMapping("/{materialId}")
    public MaterialService.MaterialDetail get(@PathVariable UUID organizationId, @PathVariable UUID materialId,
                                              Authentication authentication) {
        return materials.get(userId(authentication), organizationId, materialId);
    }

    @PatchMapping("/{materialId}")
    public MaterialService.MaterialData update(@PathVariable UUID organizationId, @PathVariable UUID materialId,
                                               @Valid @RequestBody MaterialPatch body,
                                               Authentication authentication) {
        return materials.update(userId(authentication), organizationId, materialId, body.name(), body.unit(),
                body.category(), body.brand());
    }

    @PostMapping("/{materialId}/archive")
    public MaterialService.MaterialData archive(@PathVariable UUID organizationId, @PathVariable UUID materialId,
                                                Authentication authentication) {
        return materials.archive(userId(authentication), organizationId, materialId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

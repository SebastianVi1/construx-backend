package org.andre.construx.modules.pricing.controller;


import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.pricing.service.SupplierService;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/organizations/{organizationId}/suppliers")
public class SupplierController {
    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    public record SupplierCreate(@NotBlank @Size(max = 160) String name, @Size(max = 120) String branch,
                                 @Size(max = 255) String website) {}

    /** Fields left out keep their value; an empty branch or website clears it. */
    public record SupplierPatch(@Size(max = 160) String name, @Size(max = 120) String branch,
                                @Size(max = 255) String website) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierService.SupplierData create(OrgScope org,
                                               @Valid @RequestBody SupplierCreate body) {
        return suppliers.create(org, body.name(), body.branch(), body.website());
    }

    @GetMapping
    public List<SupplierService.SupplierData> list(OrgScope org,
                                                   @RequestParam(defaultValue = "false") boolean includeArchived) {
        return suppliers.list(org, includeArchived);
    }

    @PatchMapping("/{supplierId}")
    public SupplierService.SupplierData update(OrgScope org, @PathVariable UUID supplierId,
                                               @Valid @RequestBody SupplierPatch body) {
        return suppliers.update(org, supplierId, body.name(), body.branch(),
                body.website());
    }

    @PostMapping("/{supplierId}/archive")
    public SupplierService.SupplierData archive(OrgScope org, @PathVariable UUID supplierId) {
        return suppliers.archive(org, supplierId);
    }
}

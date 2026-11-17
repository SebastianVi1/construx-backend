package org.andre.construx.modules.pricing.controller;


import org.andre.construx.modules.organization.access.OrgScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.andre.construx.modules.pricing.service.SupplierService;
import org.andre.construx.modules.pricing.service.SupplierService.SupplierInput;
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

/** Suppliers (docs/pricing.md §2.3). */
@RestController
@RequestMapping("/api/organizations/{organizationId}/suppliers")
public class SupplierController {
    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    public record SupplierCreate(@NotBlank @Size(max = 160) String name, @Size(max = 120) String branch,
                                 @Size(max = 255) String website, @Size(max = 40) String code,
                                 @Size(max = 13) String rfc, @Size(max = 255) String address,
                                 @Size(max = 120) String neighborhood, @Size(max = 160) String city,
                                 @Size(max = 80) String state, @Size(max = 10) String postalCode,
                                 @Size(max = 40) String phone, @Size(max = 40) String mobile,
                                 @Size(max = 40) String fax, @Size(max = 160) String email,
                                 @Size(max = 160) String contact, @Size(max = 500) String notes,
                                 Boolean isDefault) {
        SupplierInput input() {
            return new SupplierInput(name, branch, website, code, rfc, address, neighborhood, city, state,
                    postalCode, phone, mobile, fax, email, contact, notes, isDefault);
        }
    }

    /** Fields left out keep their value; an empty string clears an optional one. */
    public record SupplierPatch(@Size(max = 160) String name, @Size(max = 120) String branch,
                                @Size(max = 255) String website, @Size(max = 40) String code,
                                @Size(max = 13) String rfc, @Size(max = 255) String address,
                                @Size(max = 120) String neighborhood, @Size(max = 160) String city,
                                @Size(max = 80) String state, @Size(max = 10) String postalCode,
                                @Size(max = 40) String phone, @Size(max = 40) String mobile,
                                @Size(max = 40) String fax, @Size(max = 160) String email,
                                @Size(max = 160) String contact, @Size(max = 500) String notes,
                                Boolean isDefault) {
        SupplierInput input() {
            return new SupplierInput(name, branch, website, code, rfc, address, neighborhood, city, state,
                    postalCode, phone, mobile, fax, email, contact, notes, isDefault);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierService.SupplierData create(OrgScope org, @Valid @RequestBody SupplierCreate body) {
        return suppliers.create(org, body.input());
    }

    @GetMapping
    public List<SupplierService.SupplierData> list(OrgScope org,
                                                   @RequestParam(defaultValue = "false") boolean includeArchived,
                                                   @RequestParam(required = false) String query) {
        return suppliers.list(org, includeArchived, query);
    }

    @PatchMapping("/{supplierId}")
    public SupplierService.SupplierData update(OrgScope org, @PathVariable UUID supplierId,
                                               @Valid @RequestBody SupplierPatch body) {
        return suppliers.update(org, supplierId, body.input());
    }

    @PostMapping("/{supplierId}/archive")
    public SupplierService.SupplierData archive(OrgScope org, @PathVariable UUID supplierId) {
        return suppliers.archive(org, supplierId);
    }
}

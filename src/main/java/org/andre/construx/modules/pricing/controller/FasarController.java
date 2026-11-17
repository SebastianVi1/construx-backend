package org.andre.construx.modules.pricing.controller;

import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.pricing.service.FasarService;
import org.andre.construx.modules.pricing.service.FasarService.FasarInput;
import org.andre.construx.modules.pricing.service.FasarService.FasarView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FASAR parameters of the organization (docs/pricing.md §2.6). */
@RestController
@RequestMapping("/api/organizations/{organizationId}/fasar")
public class FasarController {
    private final FasarService fasar;

    public FasarController(FasarService fasar) {
        this.fasar = fasar;
    }

    @GetMapping
    public FasarView get(OrgScope org) {
        return fasar.get(org);
    }

    /** Full replacement; every Labor category is recomputed from the new set. */
    @PutMapping
    public FasarView replace(OrgScope org, @RequestBody FasarInput body) {
        return fasar.replace(org, body);
    }
}

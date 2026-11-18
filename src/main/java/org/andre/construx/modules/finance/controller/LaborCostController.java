package org.andre.construx.modules.finance.controller;

import org.andre.construx.modules.finance.service.LaborCostService;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Accrued labor cost of a Site (docs/pricing.md §6.3); budget roles only. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/labor-cost")
public class LaborCostController {
    private final LaborCostService laborCost;

    public LaborCostController(LaborCostService laborCost) {
        this.laborCost = laborCost;
    }

    /** {@code from} defaults to the Site's first Check-in day and {@code to} to today; at most 366 days. */
    @GetMapping
    public LaborCostService.LaborCostReport report(
            SiteScope site,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return laborCost.report(site, from, to);
    }

    /** Per person per day, newest day first, then by name. */
    @GetMapping("/days")
    public LaborCostService.DayPage days(
            SiteScope site,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return laborCost.days(site, membershipId, from, to, page, size);
    }
}

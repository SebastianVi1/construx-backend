package org.andre.construx.modules.finance.controller;


import org.andre.construx.modules.organization.access.OrgScope;
import org.andre.construx.modules.finance.service.OrgFinanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/finance")
public class OrgFinanceController {

    private final OrgFinanceService orgFinance;

    public OrgFinanceController(OrgFinanceService orgFinance) {
        this.orgFinance = orgFinance;
    }

    /** Company-wide budget summary: totals, per-category breakdown, and per-site status. */
    @GetMapping("/summary")
    public OrgFinanceService.OrgFinanceSummary summary(OrgScope org) {
        return orgFinance.summary(org);
    }

    /**
     * Monthly expense series for budgeted sites.
     * {@code from} defaults to January 1 of the current year; {@code to} defaults to today.
     */
    @GetMapping("/expenses/monthly")
    public OrgFinanceService.MonthlyExpenseSummary monthlyExpenses(
            OrgScope org,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return orgFinance.monthlyExpenses(org, from, to);
    }
}

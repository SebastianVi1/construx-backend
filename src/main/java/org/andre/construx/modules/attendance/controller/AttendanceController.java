package org.andre.construx.modules.attendance.controller;

import org.andre.construx.modules.attendance.service.AttendanceService;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract: {@code docs/attendance.md}. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/attendance")
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @GetMapping
    public AttendanceService.AttendanceData attendance(SiteScope site) {
        return service.attendance(site);
    }

    @PostMapping("/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceService.CheckInData checkIn(SiteScope site) {
        return service.checkIn(site);
    }
}

package org.andre.construx.modules.attendance.controller;

import org.andre.construx.modules.attendance.service.AttendanceService;
import org.andre.construx.modules.floorplan.service.SiteZones;
import org.andre.construx.modules.project.access.SiteScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Contract: {@code docs/attendance.md}. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/attendance")
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @GetMapping
    public AttendanceService.AttendanceData attendance(SiteScope site,
                                                       @RequestParam(required = false) String date) {
        return service.attendance(site, date == null ? null : day(date));
    }

    private static LocalDate day(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be a calendar day written as YYYY-MM-DD");
        }
    }

    @PostMapping("/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceService.CheckInData checkIn(
            SiteScope site, @RequestBody(required = false) AttendanceService.CheckInRequest request) {
        return service.checkIn(site, request == null ? new AttendanceService.CheckInRequest(null) : request);
    }

    @GetMapping("/zones")
    public List<SiteZones.ZoneRef> zones(SiteScope site) {
        return service.zones(site);
    }

    @DeleteMapping("/check-ins/{checkInId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(SiteScope site, @PathVariable UUID checkInId) {
        service.remove(site, checkInId);
    }
}

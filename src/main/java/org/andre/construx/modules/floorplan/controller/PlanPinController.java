package org.andre.construx.modules.floorplan.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.andre.construx.modules.floorplan.model.PhotoPhase;
import org.andre.construx.modules.floorplan.model.PinKind;
import org.andre.construx.modules.floorplan.model.PinStatus;
import org.andre.construx.modules.floorplan.service.PlanPinService;
import org.andre.construx.modules.floorplan.service.PlanPinService.PhotoUploadData;
import org.andre.construx.modules.floorplan.service.PlanPinService.PinData;
import org.andre.construx.modules.project.access.SiteScope;
import org.andre.construx.shared.audit.AuditService.AuditEventData;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations/{organizationId}/projects/{projectId}/floor-plans/{floorPlanId}/pins")
public class PlanPinController {

    private final PlanPinService pinService;

    public PlanPinController(PlanPinService pinService) {
        this.pinService = pinService;
    }

    public record PhotoUploadRequest(@NotBlank String contentType, long sizeBytes) {}

    /** Title and description lengths are entity rules, so they answer with their own error codes. */
    public record CreatePinRequest(
            @NotNull PinKind kind,
            @NotNull Double x,
            @NotNull Double y,
            String title,
            String description,
            UUID assigneeMembershipId,
            List<String> photoKeys
    ) {}

    public record UpdatePinRequest(
            String title,
            String description,
            @NotNull Double x,
            @NotNull Double y,
            UUID assigneeMembershipId,
            long version
    ) {}

    public record StatusRequest(@NotNull PinStatus status, List<String> photoKeys) {}

    public record AddPhotosRequest(@NotEmpty List<String> keys, @NotNull PhotoPhase phase) {}

    @PostMapping("/photo-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoUploadData requestPhotoUpload(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @Valid @RequestBody PhotoUploadRequest body) {
        return pinService.requestPhotoUpload(site, floorPlanId, body.contentType(), body.sizeBytes());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PinData create(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @Valid @RequestBody CreatePinRequest body) {
        return pinService.create(site, floorPlanId, new PlanPinService.CreatePinRequest(body.kind(), body.x(),
                body.y(), body.title(), body.description(), body.assigneeMembershipId(), body.photoKeys()));
    }

    /** {@code assignee=me} keeps the issues assigned to the caller. */
    @GetMapping
    public List<PinData> list(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @RequestParam(required = false) PinKind kind,
            @RequestParam(required = false) PinStatus status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) UUID zoneId) {
        return pinService.list(site, floorPlanId,
                new PlanPinService.PinFilter(kind, status, "me".equals(assignee), zoneId));
    }

    @GetMapping("/{pinId}")
    public PinData get(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId) {
        return pinService.get(site, floorPlanId, pinId);
    }

    @PatchMapping("/{pinId}")
    public PinData update(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId,
            @Valid @RequestBody UpdatePinRequest body) {
        return pinService.update(site, floorPlanId, pinId, new PlanPinService.UpdatePinRequest(body.title(),
                body.description(), body.x(), body.y(), body.assigneeMembershipId(), body.version()));
    }

    @DeleteMapping("/{pinId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId) {
        pinService.delete(site, floorPlanId, pinId);
    }

    @PostMapping("/{pinId}/status")
    public PinData changeStatus(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId,
            @Valid @RequestBody StatusRequest body) {
        return pinService.changeStatus(site, floorPlanId, pinId, body.status(), body.photoKeys());
    }

    @PostMapping("/{pinId}/photos")
    public PinData addPhotos(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId,
            @Valid @RequestBody AddPhotosRequest body) {
        return pinService.addPhotos(site, floorPlanId, pinId, body.keys(), body.phase());
    }

    @DeleteMapping("/{pinId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId,
            @PathVariable UUID photoId) {
        pinService.deletePhoto(site, floorPlanId, pinId, photoId);
    }

    @GetMapping("/{pinId}/audit")
    public List<AuditEventData> audit(
            SiteScope site,
            @PathVariable UUID floorPlanId,
            @PathVariable UUID pinId) {
        return pinService.listAudit(site, floorPlanId, pinId);
    }
}
